package com.fsck.k9.controller;


import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.fsck.k9.Account;
import com.fsck.k9.Account.DeletePolicy;
import com.fsck.k9.Account.Expunge;
import com.fsck.k9.CoreResourceProvider;
import com.fsck.k9.DI;
import com.fsck.k9.K9;
import com.fsck.k9.K9.Intents;
import com.fsck.k9.Preferences;
import com.fsck.k9.backend.BackendManager;
import com.fsck.k9.backend.api.Backend;
import com.fsck.k9.backend.api.BuildConfig;
import com.fsck.k9.backend.api.SyncConfig;
import com.fsck.k9.backend.api.SyncListener;
import com.fsck.k9.cache.EmailProviderCache;
import com.fsck.k9.controller.ControllerExtension.ControllerInternals;
import com.fsck.k9.controller.MessagingControllerCommands.PendingAppend;
import com.fsck.k9.controller.MessagingControllerCommands.PendingCommand;
import com.fsck.k9.controller.MessagingControllerCommands.PendingDelete;
import com.fsck.k9.controller.MessagingControllerCommands.PendingEmptyTrash;
import com.fsck.k9.controller.MessagingControllerCommands.PendingExpunge;
import com.fsck.k9.controller.MessagingControllerCommands.PendingMarkAllAsRead;
import com.fsck.k9.controller.MessagingControllerCommands.PendingMoveAndMarkAsRead;
import com.fsck.k9.controller.MessagingControllerCommands.PendingMoveOrCopy;
import com.fsck.k9.controller.MessagingControllerCommands.PendingSetFlag;
import com.fsck.k9.controller.ProgressBodyFactory.ProgressListener;
import com.fsck.k9.helper.Contacts;
import com.fsck.k9.mail.Address;
import com.fsck.k9.mail.AuthenticationFailedException;
import com.fsck.k9.mail.CertificateValidationException;
import com.fsck.k9.mail.FetchProfile;
import com.fsck.k9.mail.FetchProfile.Item;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.FolderClass;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.Message.RecipientType;
import com.fsck.k9.mail.MessageRetrievalListener;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.Part;
import com.fsck.k9.mail.internet.MessageExtractor;
import com.fsck.k9.mail.internet.MimeUtility;
import com.fsck.k9.mailstore.LocalFolder;
import com.fsck.k9.mailstore.LocalMessage;
import com.fsck.k9.mailstore.LocalStore;
import com.fsck.k9.mailstore.LocalStoreProvider;
import com.fsck.k9.mailstore.OutboxState;
import com.fsck.k9.mailstore.OutboxStateRepository;
import com.fsck.k9.mailstore.SendState;
import com.fsck.k9.mailstore.UnavailableStorageException;
import com.fsck.k9.notification.NotificationController;
import com.fsck.k9.notification.NotificationStrategy;
import com.fsck.k9.power.TracingPowerManager;
import com.fsck.k9.power.TracingPowerManager.TracingWakeLock;
import com.fsck.k9.search.LocalSearch;
import com.fsck.k9.search.SearchAccount;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import timber.log.Timber;

import static com.fsck.k9.K9.MAX_SEND_ATTEMPTS;
import static com.fsck.k9.helper.ExceptionHelper.getRootCauseMessage;
import static com.fsck.k9.helper.Preconditions.checkNotNull;
import static com.fsck.k9.mail.Flag.X_REMOTE_COPY_STARTED;
import static com.fsck.k9.search.LocalSearchExtensions.getAccountsFromLocalSearch;


/**
 * Starts a long running (application) Thread that will run through commands
 * that require remote mailbox access. This class is used to serialize and
 * prioritize these commands. Each method that will submit a command requires a
 * MessagingListener instance to be provided. It is expected that that listener
 * has also been added as a registered listener using addListener(). When a
 * command is to be executed, if the listener that was provided with the command
 * is no longer registered the command is skipped. The design idea for the above
 * is that when an Activity starts it registers as a listener. When it is paused
 * it removes itself. Thus, any commands that that activity submitted are
 * removed from the queue once the activity is no longer active.
 */
@SuppressWarnings("unchecked") // TODO change architecture to actually work with generics
public class MessagingController {
    public static final long INVALID_MESSAGE_ID = -1;

    public static final Set<Flag> SYNC_FLAGS = EnumSet.of(Flag.SEEN, Flag.FLAGGED, Flag.ANSWERED, Flag.FORWARDED);


    private final Context context;
    private final Contacts contacts;
    private final NotificationController notificationController;
    private final NotificationStrategy notificationStrategy;
    private final LocalStoreProvider localStoreProvider;
    private final BackendManager backendManager;

    private final Thread controllerThread;

    private final BlockingQueue<Command> queuedCommands = new PriorityBlockingQueue<>();
    private final Set<MessagingListener> listeners = new CopyOnWriteArraySet<>();
    private final ExecutorService threadPool = Executors.newCachedThreadPool();
    private final MemorizingMessagingListener memorizingMessagingListener = new MemorizingMessagingListener();
    private final UnreadMessageCountProvider unreadMessageCountProvider;
    private final CoreResourceProvider resourceProvider;


    private MessagingListener checkMailListener = null;
    private volatile boolean stopped = false;


    public static MessagingController getInstance(Context context) {
        return DI.get(MessagingController.class);
    }


    MessagingController(Context context, NotificationController notificationController,
            NotificationStrategy notificationStrategy,
            LocalStoreProvider localStoreProvider, Contacts contacts,
            UnreadMessageCountProvider unreadMessageCountProvider, CoreResourceProvider resourceProvider,
            BackendManager backendManager, List<ControllerExtension> controllerExtensions) {
        this.context = context;
        this.notificationController = notificationController;
        this.notificationStrategy = notificationStrategy;
        this.localStoreProvider = localStoreProvider;
        this.contacts = contacts;
        this.unreadMessageCountProvider = unreadMessageCountProvider;
        this.resourceProvider = resourceProvider;
        this.backendManager = backendManager;

        controllerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runInBackground();
            }
        });
        controllerThread.setName("MessagingController");
        controllerThread.start();
        addListener(memorizingMessagingListener);

        initializeControllerExtensions(controllerExtensions);
    }

    private void initializeControllerExtensions(List<ControllerExtension> controllerExtensions) {
        if (controllerExtensions.isEmpty()) {
            return;
        }

        ControllerInternals internals = new ControllerInternals() {
            @Override
            public void put(@NotNull String description, @Nullable MessagingListener listener,
                    @NotNull Runnable runnable) {
                MessagingController.this.put(description, listener, runnable);
            }

            @Override
            public void putBackground(@NotNull String description, @Nullable MessagingListener listener,
                    @NotNull Runnable runnable) {
                MessagingController.this.putBackground(description, listener, runnable);
            }
        };

        for (ControllerExtension extension : controllerExtensions) {
            extension.init(this, backendManager, internals);
        }
    }

    @VisibleForTesting
    void stop() throws InterruptedException {
        stopped = true;
        controllerThread.interrupt();
        controllerThread.join(1000L);
    }

    private void runInBackground() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        while (!stopped) {
            String commandDescription = null;
            try {
                final Command command = queuedCommands.take();

                if (command != null) {
                    commandDescription = command.description;

                    Timber.i("Running command '%s', seq = %s (%s priority)",
                            command.description,
                            command.sequence,
                            command.isForegroundPriority ? "foreground" : "background");

                    try {
                        command.runnable.run();
                    } catch (UnavailableAccountException e) {
                        // retry later
                        new Thread() {
                            @Override
                            public void run() {
                                try {
                                    sleep(30 * 1000);
                                    queuedCommands.put(command);
                                } catch (InterruptedException e) {
                                    Timber.e("Interrupted while putting a pending command for an unavailable account " +
                                            "back into the queue. THIS SHOULD NEVER HAPPEN.");
                                }
                            }
                        }.start();
                    }

                    Timber.i(" Command '%s' completed", command.description);
                }
            } catch (Exception e) {
                Timber.e(e, "Error running command '%s'", commandDescription);
            }
        }
    }

    private void put(String description, MessagingListener listener, Runnable runnable) {
        putCommand(queuedCommands, description, listener, runnable, true);
    }

    private void putBackground(String description, MessagingListener listener, Runnable runnable) {
        putCommand(queuedCommands, description, listener, runnable, false);
    }

    private void putCommand(BlockingQueue<Command> queue, String description, MessagingListener listener,
            Runnable runnable, boolean isForeground) {
        int retries = 10;
        Exception e = null;
        while (retries-- > 0) {
            try {
                Command command = new Command();
                command.listener = listener;
                command.runnable = runnable;
                command.description = description;
                command.isForegroundPriority = isForeground;
                queue.put(command);
                return;
            } catch (InterruptedException ie) {
                SystemClock.sleep(200);
                e = ie;
            }
        }
        throw new Error(e);
    }

    private Backend getBackend(Account account) {
        return backendManager.getBackend(account);
    }

    private LocalStore getLocalStoreOrThrow(Account account) {
        try {
            return localStoreProvider.getInstance(account);
        } catch (MessagingException e) {
            throw new IllegalStateException("Couldn't get LocalStore for account " + account.getDescription());
        }
    }

    private String getFolderServerId(Account account, long folderId) throws MessagingException {
        LocalStore localStore = getLocalStoreOrThrow(account);
        return localStore.getFolderServerId(folderId);
    }

    private long getFolderId(Account account, String folderServerId) throws MessagingException {
        LocalStore localStore = getLocalStoreOrThrow(account);
        return localStore.getFolderId(folderServerId);
    }

    public void addListener(MessagingListener listener) {
        listeners.add(listener);
        refreshListener(listener);
    }

    public void refreshListener(MessagingListener listener) {
        if (listener != null) {
            memorizingMessagingListener.refreshOther(listener);
        }
    }

    public void removeListener(MessagingListener listener) {
        listeners.remove(listener);
    }

    public Set<MessagingListener> getListeners() {
        return listeners;
    }


    public Set<MessagingListener> getListeners(MessagingListener listener) {
        if (listener == null) {
            return listeners;
        }

        Set<MessagingListener> listeners = new HashSet<>(this.listeners);
        listeners.add(listener);
        return listeners;

    }


    private void suppressMessages(Account account, List<LocalMessage> messages) {
        EmailProviderCache cache = EmailProviderCache.getCache(account.getUuid(), context);
        cache.hideMessages(messages);
    }

    private void unsuppressMessages(Account account, List<LocalMessage> messages) {
        EmailProviderCache cache = EmailProviderCache.getCache(account.getUuid(), context);
        cache.unhideMessages(messages);
    }

    public boolean isMessageSuppressed(LocalMessage message) {
        long messageId = message.getDatabaseId();
        long folderId = message.getFolder().getDatabaseId();

        EmailProviderCache cache = EmailProviderCache.getCache(message.getFolder().getAccountUuid(), context);
        return cache.isMessageHidden(messageId, folderId);
    }

    private void setFlagInCache(final Account account, final List<Long> messageIds,
            final Flag flag, final boolean newState) {

        EmailProviderCache cache = EmailProviderCache.getCache(account.getUuid(), context);
        String columnName = LocalStore.getColumnNameForFlag(flag);
        String value = Integer.toString((newState) ? 1 : 0);
        cache.setValueForMessages(messageIds, columnName, value);
    }

    private void removeFlagFromCache(final Account account, final List<Long> messageIds,
            final Flag flag) {

        EmailProviderCache cache = EmailProviderCache.getCache(account.getUuid(), context);
        String columnName = LocalStore.getColumnNameForFlag(flag);
        cache.removeValueForMessages(messageIds, columnName);
    }

    private void setFlagForThreadsInCache(final Account account, final List<Long> threadRootIds,
            final Flag flag, final boolean newState) {

        EmailProviderCache cache = EmailProviderCache.getCache(account.getUuid(), context);
        String columnName = LocalStore.getColumnNameForFlag(flag);
        String value = Integer.toString((newState) ? 1 : 0);
        cache.setValueForThreads(threadRootIds, columnName, value);
    }

    private void removeFlagForThreadsFromCache(final Account account, final List<Long> messageIds,
            final Flag flag) {

        EmailProviderCache cache = EmailProviderCache.getCache(account.getUuid(), context);
        String columnName = LocalStore.getColumnNameForFlag(flag);
        cache.removeValueForThreads(messageIds, columnName);
    }

    public void refreshFolderList(final Account account) {
        put("refreshFolderList", null, () -> refreshFolderListSynchronous(account));
    }

    public void refreshFolderListSynchronous(Account account) {
        try {
            Backend backend = getBackend(account);
            backend.refreshFolderList();
        } catch (Exception e) {
            Timber.e(e);
        }
    }

    /**
     * Find all messages in any local account which match the query 'query'
     */
    public void searchLocalMessages(final LocalSearch search, final MessagingListener listener) {
        threadPool.execute(new Runnable() {
            @Override
            public void run() {
                searchLocalMessagesSynchronous(search, listener);
            }
        });
    }

    @VisibleForTesting
    void searchLocalMessagesSynchronous(final LocalSearch search, final MessagingListener listener) {
        Preferences preferences = Preferences.getPreferences(context);
        List<Account> searchAccounts = getAccountsFromLocalSearch(search, preferences);

        for (final Account account : searchAccounts) {

            // Collecting statistics of the search result
            MessageRetrievalListener<LocalMessage> retrievalListener = new MessageRetrievalListener<LocalMessage>() {
                @Override
                public void messageStarted(String message, int number, int ofTotal) {
                }

                @Override
                public void messagesFinished(int number) {
                }

                @Override
                public void messageFinished(LocalMessage message, int number, int ofTotal) {
                    if (!isMessageSuppressed(message)) {
                        List<LocalMessage> messages = new ArrayList<>();

                        messages.add(message);
                        if (listener != null) {
                            listener.listLocalMessagesAddMessages(account, null, messages);
                        }
                    }
                }
            };

            // build and do the query in the localstore
            try {
                LocalStore localStore = localStoreProvider.getInstance(account);
                localStore.searchForMessages(retrievalListener, search);
            } catch (Exception e) {
                Timber.e(e);
            }
        }

        if (listener != null) {
            listener.listLocalMessagesFinished();
        }
    }

    public Future<?> searchRemoteMessages(final String acctUuid, final String folderServerId, final String query,
            final Set<Flag> requiredFlags, final Set<Flag> forbiddenFlags, final MessagingListener listener) {
        Timber.i("searchRemoteMessages (acct = %s, folderServerId = %s, query = %s)", acctUuid, folderServerId, query);

        return threadPool.submit(new Runnable() {
            @Override
            public void run() {
                searchRemoteMessagesSynchronous(acctUuid, folderServerId, query, requiredFlags, forbiddenFlags,
                        listener);
            }
        });
    }

    @VisibleForTesting
    void searchRemoteMessagesSynchronous(final String acctUuid, final String folderServerId, final String query,
            final Set<Flag> requiredFlags, final Set<Flag> forbiddenFlags, final MessagingListener listener) {
        final Account acct = Preferences.getPreferences(context).getAccount(acctUuid);

        if (listener != null) {
            listener.remoteSearchStarted(folderServerId);
        }

        List<String> extraResults = new ArrayList<>();
        try {
            LocalStore localStore = localStoreProvider.getInstance(acct);

            LocalFolder localFolder = localStore.getFolder(folderServerId);
            if (localFolder == null) {
                throw new MessagingException("Folder not found");
            }

            Backend backend = getBackend(acct);

            List<String> messageServerIds = backend.search(folderServerId, query, requiredFlags, forbiddenFlags);

            Timber.i("Remote search got %d results", messageServerIds.size());

            // There's no need to fetch messages already completely downloaded
            messageServerIds = localFolder.extractNewMessages(messageServerIds);

            if (listener != null) {
                listener.remoteSearchServerQueryComplete(folderServerId, messageServerIds.size(),
                        acct.getRemoteSearchNumResults());
            }

            int resultLimit = acct.getRemoteSearchNumResults();
            if (resultLimit > 0 && messageServerIds.size() > resultLimit) {
                extraResults = messageServerIds.subList(resultLimit, messageServerIds.size());
                messageServerIds = messageServerIds.subList(0, resultLimit);
            }

            loadSearchResultsSynchronous(acct, messageServerIds, localFolder);
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                Timber.i(e, "Caught exception on aborted remote search; safe to ignore.");
            } else {
                Timber.e(e, "Could not complete remote search");
                if (listener != null) {
                    listener.remoteSearchFailed(null, e.getMessage());
                }
                Timber.e(e);
            }
        } finally {
            if (listener != null) {
                listener.remoteSearchFinished(folderServerId, 0, acct.getRemoteSearchNumResults(), extraResults);
            }
        }

    }

    public void loadSearchResults(final Account account, final String folderServerId,
            final List<String> messageServerIds, final MessagingListener listener) {
        threadPool.execute(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.enableProgressIndicator(true);
                }
                try {
                    LocalStore localStore = localStoreProvider.getInstance(account);

                    if (localStore == null) {
                        throw new MessagingException("Could not get store");
                    }

                    LocalFolder localFolder = localStore.getFolder(folderServerId);
                    if (localFolder == null) {
                        throw new MessagingException("Folder not found");
                    }

                    loadSearchResultsSynchronous(account, messageServerIds, localFolder);
                } catch (MessagingException e) {
                    Timber.e(e, "Exception in loadSearchResults");
                } finally {
                    if (listener != null) {
                        listener.enableProgressIndicator(false);
                    }
                }
            }
        });
    }

    private void loadSearchResultsSynchronous(Account account, List<String> messageServerIds, LocalFolder localFolder)
            throws MessagingException {

        FetchProfile fetchProfile = new FetchProfile();
        fetchProfile.add(FetchProfile.Item.FLAGS);
        fetchProfile.add(FetchProfile.Item.ENVELOPE);
        fetchProfile.add(FetchProfile.Item.STRUCTURE);

        Backend backend = getBackend(account);
        String folderServerId = localFolder.getServerId();

        for (String messageServerId : messageServerIds) {
            LocalMessage localMessage = localFolder.getMessage(messageServerId);

            if (localMessage == null) {
                Message message = backend.fetchMessage(folderServerId, messageServerId, fetchProfile);
                localFolder.appendMessages(Collections.singletonList(message));
            }
        }
    }


    public void loadMoreMessages(Account account, String folder, MessagingListener listener) {
        try {
            LocalStore localStore = localStoreProvider.getInstance(account);
            LocalFolder localFolder = localStore.getFolder(folder);
            if (localFolder.getVisibleLimit() > 0) {
                localFolder.setVisibleLimit(localFolder.getVisibleLimit() + account.getDisplayCount());
            }
            synchronizeMailbox(account, folder, listener);
        } catch (MessagingException me) {
            throw new RuntimeException("Unable to set visible limit on folder", me);
        }
    }

    /**
     * Start background synchronization of the specified folder.
     */
    public void synchronizeMailbox(final Account account, final String folder, final MessagingListener listener) {
        putBackground("synchronizeMailbox", listener, new Runnable() {
            @Override
            public void run() {
                synchronizeMailboxSynchronous(account, folder, listener);
            }
        });
    }

    /**
     * Start foreground synchronization of the specified folder. This is generally only called
     * by synchronizeMailbox.
     * <p>
     * TODO Break this method up into smaller chunks.
     */
    @VisibleForTesting
    void synchronizeMailboxSynchronous(final Account account, final String folder, final MessagingListener listener) {
        Backend remoteMessageStore = getBackend(account);
        syncFolder(account, folder, listener, remoteMessageStore);
    }

    private void syncFolder(Account account, String folder, MessagingListener listener, Backend remoteMessageStore) {
        Exception commandException = null;
        try {
            processPendingCommandsSynchronous(account);
        } catch (Exception e) {
            Timber.e(e, "Failure processing command, but allow message sync attempt");
            commandException = e;
        }

        // We don't ever sync the Outbox
        if (folder.equals(account.getOutboxFolder())) {
            return;
        }

        SyncConfig syncConfig = createSyncConfig(account);

        ControllerSyncListener syncListener = new ControllerSyncListener(account, listener);
        remoteMessageStore.sync(folder, syncConfig, syncListener);

        if (commandException != null && !syncListener.syncFailed) {
            String rootMessage = getRootCauseMessage(commandException);
            Timber.e("Root cause failure in %s:%s was '%s'", account.getDescription(), folder, rootMessage);
            updateFolderStatus(account, folder, rootMessage);
            listener.synchronizeMailboxFailed(account, folder, rootMessage);
        }
    }

    private SyncConfig createSyncConfig(Account account) {
        return new SyncConfig(
                    account.getExpungePolicy().toBackendExpungePolicy(),
                    account.getEarliestPollDate(),
                    account.isSyncRemoteDeletions(),
                    account.getMaximumAutoDownloadMessageSize(),
                    K9.DEFAULT_VISIBLE_LIMIT,
                    SYNC_FLAGS);
    }

    private void updateFolderStatus(Account account, String folderServerId, String status) {
        try {
            LocalStore localStore = localStoreProvider.getInstance(account);
            LocalFolder localFolder = localStore.getFolder(folderServerId);
            localFolder.setStatus(status);
        } catch (MessagingException e) {
            Timber.w(e, "Couldn't update folder status for folder %s", folderServerId);
        }
    }

    public void handleAuthenticationFailure(Account account, boolean incoming) {
        notificationController.showAuthenticationErrorNotification(account, incoming);
    }

    private static void closeFolder(LocalFolder f) {
        if (f != null) {
            f.close();
        }
    }

    private void queuePendingCommand(Account account, PendingCommand command) {
        try {
            LocalStore localStore = localStoreProvider.getInstance(account);
            localStore.addPendingCommand(command);
        } catch (Exception e) {
            throw new RuntimeException("Unable to enqueue pending command", e);
        }
    }

    private void processPendingCommands(final Account account) {
        putBackground("processPendingCommands", null, new Runnable() {
            @Override
            public void run() {
                try {
                    processPendingCommandsSynchronous(account);
                } catch (UnavailableStorageException e) {
                    Timber.i("Failed to process pending command because storage is not available - " +
                            "trying again later.");
                    throw new UnavailableAccountException(e);
                } catch (MessagingException me) {
                    Timber.e(me, "processPendingCommands");

                    /*
                     * Ignore any exceptions from the commands. Commands will be processed
                     * on the next round.
                     */
                }
            }
        });
    }

    public void processPendingCommandsSynchronous(Account account) throws MessagingException {
        LocalStore localStore = localStoreProvider.getInstance(account);
        List<PendingCommand> commands = localStore.getPendingCommands();

        PendingCommand processingCommand = null;
        try {
            for (PendingCommand command : commands) {
                processingCommand = command;
                Timber.d("Processing pending command '%s'", command);

                /*
                 * We specifically do not catch any exceptions here. If a command fails it is
                 * most likely due to a server or IO error and it must be retried before any
                 * other command processes. This maintains the order of the commands.
                 */
                try {
                    command.execute(this, account);

                    localStore.removePendingCommand(command);

                    Timber.d("Done processing pending command '%s'", command);
                } catch (MessagingException me) {
                    if (me.isPermanentFailure()) {
                        Timber.e("Failure of command '%s' was permanent, removing command from queue", command);
                        localStore.removePendingCommand(processingCommand);
                    } else {
                        throw me;
                    }
                }
            }
        } catch (MessagingException me) {
            notifyUserIfCertificateProblem(account, me, true);
            Timber.e(me, "Could not process command '%s'", processingCommand);
            throw me;
        }
    }

    /**
     * Process a pending append message command. This command uploads a local message to the
     * server, first checking to be sure that the server message is not newer than
     * the local message. Once the local message is successfully processed it is deleted so
     * that the server message will be synchronized down without an additional copy being
     * created.
     */
    void processPendingAppend(PendingAppend command, Account account) throws MessagingException {
        LocalStore localStore = localStoreProvider.getInstance(account);
        LocalFolder localFolder = localStore.getFolder(command.folderId);
        try {
            localFolder.open();

            String folderServerId = localFolder.getServerId();
            String uid = command.uid;

            LocalMessage localMessage = localFolder.getMessage(uid);
            if (localMessage == null) {
                return;
            }

            if (!localMessage.getUid().startsWith(K9.LOCAL_UID_PREFIX)) {
                //FIXME: This should never happen. Throw in debug builds.
                return;
            }

            Backend backend = getBackend(account);

            if (localMessage.isSet(Flag.X_REMOTE_COPY_STARTED)) {
                Timber.w("Local message with uid %s has flag %s  already set, checking for remote message with " +
                        "same message id", localMessage.getUid(), X_REMOTE_COPY_STARTED);

                String messageServerId = backend.findByMessageId(folderServerId, localMessage.getMessageId());
                if (messageServerId != null) {
                    Timber.w("Local message has flag %s already set, and there is a remote message with uid %s, " +
                            "assuming message was already copied and aborting this copy",
                            X_REMOTE_COPY_STARTED, messageServerId);

                    String oldUid = localMessage.getUid();
                    localMessage.setUid(messageServerId);
                    localFolder.changeUid(localMessage);

                    for (MessagingListener l : getListeners()) {
                        l.messageUidChanged(account, folderServerId, oldUid, localMessage.getUid());
                    }

                    return;
                } else {
                    Timber.w("No remote message with message-id found, proceeding with append");
                }
            }

            /*
             * If the message does not exist remotely we just upload it and then
             * update our local copy with the new uid.
             */
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.BODY);
            localFolder.fetch(Collections.singletonList(localMessage), fp, null);
            String oldUid = localMessage.getUid();
            localMessage.setFlag(Flag.X_REMOTE_COPY_STARTED, true);

            String messageServerId = backend.uploadMessage(folderServerId, localMessage);

            if (messageServerId == null) {
                // We didn't get the server UID of the uploaded message. Remove the local message now. The uploaded
                // version will be downloaded during the next sync.
                localFolder.destroyMessages(Collections.singletonList(localMessage));
            } else {
                localMessage.setUid(messageServerId);
                localFolder.changeUid(localMessage);

                for (MessagingListener l : getListeners()) {
                    l.messageUidChanged(account, folderServerId, oldUid, localMessage.getUid());
                }
            }
        } finally {
            localFolder.close();
        }
    }

    private void queueMoveOrCopy(Account account, long srcFolderId, long destFolderId, MoveOrCopyFlavor operation,
            Map<String, String> uidMap) {
        PendingCommand command;
        switch (operation) {
            case MOVE:
                command = PendingMoveOrCopy.create(srcFolderId, destFolderId, false, uidMap);
                break;
            case COPY:
                command = PendingMoveOrCopy.create(srcFolderId, destFolderId, true, uidMap);
                break;
            case MOVE_AND_MARK_AS_READ:
                command = PendingMoveAndMarkAsRead.create(srcFolderId, destFolderId, uidMap);
                break;
            default:
                return;
        }
        queuePendingCommand(account, command);
    }

    void processPendingMoveOrCopy(PendingMoveOrCopy command, Account account) throws MessagingException {
        long srcFolder = command.srcFolderId;
        long destFolder = command.destFolderId;
        MoveOrCopyFlavor operation = command.isCopy ? MoveOrCopyFlavor.COPY : MoveOrCopyFlavor.MOVE;

        Map<String, String> newUidMap = command.newUidMap;
        List<String> uids = newUidMap != null ? new ArrayList<>(newUidMap.keySet()) : command.uids;

        processPendingMoveOrCopy(account, srcFolder, destFolder, uids, operation, newUidMap);
    }

    void processPendingMoveAndRead(PendingMoveAndMarkAsRead command, Account account) throws MessagingException {
        long srcFolder = command.srcFolderId;
        long destFolder = command.destFolderId;
        Map<String, String> newUidMap = command.newUidMap;
        List<String> uids = new ArrayList<>(newUidMap.keySet());

        processPendingMoveOrCopy(account, srcFolder, destFolder, uids,
                MoveOrCopyFlavor.MOVE_AND_MARK_AS_READ, newUidMap);
    }

    @VisibleForTesting
    void processPendingMoveOrCopy(Account account, long srcFolderId, long destFolderId, List<String> uids,
                                  MoveOrCopyFlavor operation, Map<String, String> newUidMap) throws MessagingException {
        checkNotNull(newUidMap);

        LocalStore localStore = localStoreProvider.getInstance(account);

        LocalFolder localSourceFolder = localStore.getFolder(srcFolderId);
        localSourceFolder.open();
        String srcFolderServerId = localSourceFolder.getServerId();

        LocalFolder localDestFolder = localStore.getFolder(destFolderId);
        localDestFolder.open();
        String destFolderServerId = localDestFolder.getServerId();

        Backend backend = getBackend(account);

        Map<String, String> remoteUidMap;
        switch (operation) {
            case COPY:
                remoteUidMap = backend.copyMessages(srcFolderServerId, destFolderServerId, uids);
                break;
            case MOVE:
                remoteUidMap = backend.moveMessages(srcFolderServerId, destFolderServerId, uids);
                break;
            case MOVE_AND_MARK_AS_READ:
                remoteUidMap = backend.moveMessagesAndMarkAsRead(srcFolderServerId, destFolderServerId, uids);
                break;
            default:
                throw new RuntimeException("Unsupported messaging operation");
        }

        if (operation != MoveOrCopyFlavor.COPY) {
            if (backend.getSupportsExpunge() && account.getExpungePolicy() == Expunge.EXPUNGE_IMMEDIATELY) {
                Timber.i("processingPendingMoveOrCopy expunging folder %s:%s", account.getDescription(), srcFolderServerId);
                backend.expungeMessages(srcFolderServerId, uids);
            }

            destroyPlaceholderMessages(localSourceFolder, uids);
        }

        // TODO: Change Backend interface to ensure we never receive null for remoteUidMap
        if (remoteUidMap == null) {
            remoteUidMap = Collections.emptyMap();
        }

        // Update local messages (that currently have local UIDs) with new server IDs
        for (String uid : uids) {
            String localUid = newUidMap.get(uid);
            String newUid = remoteUidMap.get(uid);

            LocalMessage localMessage = localDestFolder.getMessage(localUid);
            if (localMessage == null) {
                // Local message no longer exists
                continue;
            }

            if (newUid != null) {
                // Update local message with new server ID
                localMessage.setUid(newUid);
                localDestFolder.changeUid(localMessage);
                for (MessagingListener l : getListeners()) {
                    l.messageUidChanged(account, destFolderServerId, localUid, newUid);
                }
            } else {
                // New server ID wasn't provided. Remove local message.
                localMessage.destroy();
            }
        }
    }

    private void destroyPlaceholderMessages(LocalFolder localFolder, List<String> uids) throws MessagingException {
        for (String uid : uids) {
            LocalMessage placeholderMessage = localFolder.getMessage(uid);
            if (placeholderMessage == null) {
                continue;
            }

            if (placeholderMessage.isSet(Flag.DELETED)) {
                placeholderMessage.destroy();
            } else {
                Timber.w("Expected local message %s in folder %s to be a placeholder, but DELETE flag wasn't set",
                        uid, localFolder.getServerId());

                if (BuildConfig.DEBUG) {
                    throw new AssertionError("Placeholder message must have the DELETED flag set");
                }
            }
        }
    }

    private void queueSetFlag(Account account, long folderId, boolean newState, Flag flag, List<String> uids) {
        putBackground("queueSetFlag", null, () -> {
            PendingCommand command = PendingSetFlag.create(folderId, newState, flag, uids);
            queuePendingCommand(account, command);
            processPendingCommands(account);
        });
    }

    /**
     * Processes a pending mark read or unread command.
     */
    void processPendingSetFlag(PendingSetFlag command, Account account) throws MessagingException {
        Backend backend = getBackend(account);
        String folderServerId = getFolderServerId(account, command.folderId);
        backend.setFlag(folderServerId, command.uids, command.flag, command.newState);
    }

    private void queueDelete(Account account, long folderId, List<String> uids) {
        putBackground("queueDelete", null, () -> {
            PendingCommand command = PendingDelete.create(folderId, uids);
            queuePendingCommand(account, command);
            processPendingCommands(account);
        });
    }

    void processPendingDelete(PendingDelete command, Account account) throws MessagingException {
        Backend backend = getBackend(account);
        String folderServerId = getFolderServerId(account, command.folderId);
        backend.deleteMessages(folderServerId, command.uids);
    }

    private void queueExpunge(Account account, long folderId) {
        PendingCommand command = PendingExpunge.create(folderId);
        queuePendingCommand(account, command);
    }

    void processPendingExpunge(PendingExpunge command, Account account) throws MessagingException {
        Backend backend = getBackend(account);
        String folderServerId = getFolderServerId(account, command.folderId);
        backend.expunge(folderServerId);
    }

    void processPendingMarkAllAsRead(PendingMarkAllAsRead command, Account account) throws MessagingException {
        LocalStore localStore = localStoreProvider.getInstance(account);
        LocalFolder localFolder = localStore.getFolder(command.folderId);

        String folderServerId;
        try {
            localFolder.open();
            folderServerId = localFolder.getServerId();

            Timber.i("Marking all messages in %s:%s as read", account, folderServerId);

            // TODO: Make this one database UPDATE operation
            List<LocalMessage> messages = localFolder.getMessages(null, false);
            for (Message message : messages) {
                if (!message.isSet(Flag.SEEN)) {
                    message.setFlag(Flag.SEEN, true);
                }
            }

            for (MessagingListener l : getListeners()) {
                l.folderStatusChanged(account, folderServerId);
            }
        } finally {
            localFolder.close();
        }

        Backend backend = getBackend(account);
        if (backend.getSupportsSeenFlag()) {
            backend.markAllAsRead(folderServerId);
        }
    }

    public void markAllMessagesRead(Account account, long folderId) {
        PendingCommand command = PendingMarkAllAsRead.create(folderId);
        queuePendingCommand(account, command);
        processPendingCommands(account);
    }

    public void setFlag(final Account account, final List<Long> messageIds, final Flag flag,
            final boolean newState) {

        setFlagInCache(account, messageIds, flag, newState);

        threadPool.execute(new Runnable() {
            @Override
            public void run() {
                setFlagSynchronous(account, messageIds, flag, newState, false);
            }
        });
    }

    public void setFlagForThreads(final Account account, final List<Long> threadRootIds,
            final Flag flag, final boolean newState) {

        setFlagForThreadsInCache(account, threadRootIds, flag, newState);

        threadPool.execute(new Runnable() {
            @Override
            public void run() {
                setFlagSynchronous(account, threadRootIds, flag, newState, true);
            }
        });
    }

    private void setFlagSynchronous(final Account account, final List<Long> ids,
            final Flag flag, final boolean newState, final boolean threadedList) {

        LocalStore localStore;
        try {
            localStore = localStoreProvider.getInstance(account);
        } catch (MessagingException e) {
            Timber.e(e, "Couldn't get LocalStore instance");
            return;
        }

        // Update affected messages in the database. This should be as fast as possible so the UI
        // can be updated with the new state.
        try {
            if (threadedList) {
                localStore.setFlagForThreads(ids, flag, newState);
                removeFlagForThreadsFromCache(account, ids, flag);
            } else {
                localStore.setFlag(ids, flag, newState);
                removeFlagFromCache(account, ids, flag);
            }
        } catch (MessagingException e) {
            Timber.e(e, "Couldn't set flags in local database");
        }

        // Read folder name and UID of messages from the database
        Map<String, List<String>> folderMap;
        try {
            folderMap = localStore.getFoldersAndUids(ids, threadedList);
        } catch (MessagingException e) {
            Timber.e(e, "Couldn't get folder name and UID of messages");
            return;
        }

        // Loop over all folders
        for (Entry<String, List<String>> entry : folderMap.entrySet()) {
            String folderServerId = entry.getKey();

            // Notify listeners of changed folder status
            for (MessagingListener l : getListeners()) {
                l.folderStatusChanged(account, folderServerId);
            }

            // TODO: Skip the remote part for all local-only folders

            // Send flag change to server
            try {
                long folderId = getFolderId(account, folderServerId);
                queueSetFlag(account, folderId, newState, flag, entry.getValue());
                processPendingCommands(account);
            } catch (MessagingException e) {
                Timber.e(e, "Error while trying to set flags");
            }
        }
    }

    /**
     * Set or remove a flag for a set of messages in a specific folder.
     * <p>
     * <p>
     * The {@link Message} objects passed in are updated to reflect the new flag state.
     * </p>
     *
     * @param account
     *         The account the folder containing the messages belongs to.
     * @param folderServerId
     *         The server ID of the folder.
     * @param messages
     *         The messages to change the flag for.
     * @param flag
     *         The flag to change.
     * @param newState
     *         {@code true}, if the flag should be set. {@code false} if it should be removed.
     */
    public void setFlag(Account account, String folderServerId, List<LocalMessage> messages, Flag flag,
            boolean newState) {
        // TODO: Put this into the background, but right now some callers depend on the message
        //       objects being modified right after this method returns.
        LocalFolder localFolder = null;
        try {
            LocalStore localStore = localStoreProvider.getInstance(account);
            localFolder = localStore.getFolder(folderServerId);
            localFolder.open();

            // Update the messages in the local store
            localFolder.setFlags(messages, Collections.singleton(flag), newState);

            for (MessagingListener l : getListeners()) {
                l.folderStatusChanged(account, folderServerId);
            }


            /*
             * Handle the remote side
             */

            // TODO: Skip the remote part for all local-only folders

            List<String> uids = getUidsFromMessages(messages);
            queueSetFlag(account, localFolder.getDatabaseId(), newState, flag, uids);
            processPendingCommands(account);
        } catch (MessagingException me) {
            throw new RuntimeException(me);
        } finally {
            closeFolder(localFolder);
        }
    }

    /**
     * Set or remove a flag for a message referenced by message UID.
     *
     * @param account
     *         The account the folder containing the message belongs to.
     * @param folderServerId
     *         The server ID of the folder.
     * @param uid
     *         The UID of the message to change the flag for.
     * @param flag
     *         The flag to change.
     * @param newState
     *         {@code true}, if the flag should be set. {@code false} if it should be removed.
     */
    public void setFlag(Account account, String folderServerId, String uid, Flag flag,
            boolean newState) {
        LocalFolder localFolder = null;
        try {
            LocalStore localStore = localStoreProvider.getInstance(account);
            localFolder = localStore.getFolder(folderServerId);
            localFolder.open();

            LocalMessage message = localFolder.getMessage(uid);
            if (message != null) {
                setFlag(account, folderServerId, Collections.singletonList(message), flag, newState);
            }
        } catch (MessagingException me) {
            throw new RuntimeException(me);
        } finally {
            closeFolder(localFolder);
        }
    }

    public void clearAllPending(final Account account) {
        try {
            Timber.w("Clearing pending commands!");
            LocalStore localStore = localStoreProvider.getInstance(account);
            localStore.removePendingCommands();
        } catch (MessagingException me) {
            Timber.e(me, "Unable to clear pending command");
        }
    }

    public void loadMessageRemotePartial(final Account account, final String folder,
            final String uid, final MessagingListener listener) {
        put("loadMessageRemotePartial", listener, new Runnable() {
            @Override
            public void run() {
                loadMessageRemoteSynchronous(account, folder, uid, listener, true);
            }
        });
    }

    //TODO: Fix the callback mess. See GH-782
    public void loadMessageRemote(final Account account, final String folder,
            final String uid, final MessagingListener listener) {
        put("loadMessageRemote", listener, new Runnable() {
            @Override
            public void run() {
                loadMessageRemoteSynchronous(account, folder, uid, listener, false);
            }
        });
    }

    private boolean loadMessageRemoteSynchronous(final Account account, final String folder,
            final String uid, final MessagingListener listener, final boolean loadPartialFromSearch) {
        LocalFolder localFolder = null;
        try {
            LocalStore localStore = localStoreProvider.getInstance(account);
            localFolder = localStore.getFolder(folder);
            localFolder.open();

            LocalMessage message = localFolder.getMessage(uid);

            if (uid.startsWith(K9.LOCAL_UID_PREFIX)) {
                Timber.w("Message has local UID so cannot download fully.");
                // ASH move toast
                android.widget.Toast.makeText(context,
                        "Message has local UID so cannot download fully",
                        android.widget.Toast.LENGTH_LONG).show();
                // TODO: Using X_DOWNLOADED_FULL is wrong because it's only a partial message. But
                // one we can't download completely. Maybe add a new flag; X_PARTIAL_MESSAGE ?
                message.setFlag(Flag.X_DOWNLOADED_FULL, true);
                message.setFlag(Flag.X_DOWNLOADED_PARTIAL, false);
            } else {
                Backend backend = getBackend(account);

                if (loadPartialFromSearch) {
                    SyncConfig syncConfig = createSyncConfig(account);
                    backend.downloadMessage(syncConfig, folder, uid);
                } else {
                    FetchProfile fp = new FetchProfile();
                    fp.add(FetchProfile.Item.BODY);
                    fp.add(FetchProfile.Item.FLAGS);
                    Message remoteMessage = backend.fetchMessage(folder, uid, fp);
                    localFolder.appendMessages(Collections.singletonList(remoteMessage));
                }

                message = localFolder.getMessage(uid);

                if (!loadPartialFromSearch) {
                    message.setFlag(Flag.X_DOWNLOADED_FULL, true);
                }
            }

            // now that we have the full message, refresh the headers
            for (MessagingListener l : getListeners(listener)) {
                l.loadMessageRemoteFinished(account, folder, uid);
            }

            return true;
        } catch (Exception e) {
            for (MessagingListener l : getListeners(listener)) {
                l.loadMessageRemoteFailed(account, folder, uid, e);
            }
            notifyUserIfCertificateProblem(account, e, true);
            Timber.e(e, "Error while loading remote message");
            return false;
        } finally {
            closeFolder(localFolder);
        }
    }

    public LocalMessage loadMessage(Account account, String folderServerId, String uid) throws MessagingException {
        LocalStore localStore = localStoreProvider.getInstance(account);
        LocalFolder localFolder = localStore.getFolder(folderServerId);
        localFolder.open();

        LocalMessage message = localFolder.getMessage(uid);
        if (message == null || message.getDatabaseId() == 0) {
            throw new IllegalArgumentException("Message not found: folder=" + folderServerId + ", uid=" + uid);
        }

        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.BODY);
        localFolder.fetch(Collections.singletonList(message), fp, null);
        localFolder.close();

        notificationController.removeNewMailNotification(account, message.makeMessageReference());
        markMessageAsReadOnView(account, message);

        return message;
    }

    public LocalMessage loadMessageMetadata(Account account, String folderServerId, String uid) throws MessagingException {
        LocalStore localStore = localStoreProvider.getInstance(account);
        LocalFolder localFolder = localStore.getFolder(folderServerId);
        localFolder.open();

        LocalMessage message = localFolder.getMessage(uid);
        if (message == null || message.getDatabaseId() == 0) {
            throw new IllegalArgumentException("Message not found: folder=" + folderServerId + ", uid=" + uid);
        }

        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.ENVELOPE);
        localFolder.fetch(Collections.singletonList(message), fp, null);
        localFolder.close();

        return message;
    }

    private void markMessageAsReadOnView(Account account, LocalMessage message)
            throws MessagingException {

        if (account.isMarkMessageAsReadOnView() && !message.isSet(Flag.SEEN)) {
            List<Long> messageIds = Collections.singletonList(message.getDatabaseId());
            setFlag(account, messageIds, Flag.SEEN, true);

            message.setFlagInternal(Flag.SEEN, true);
        }
    }

    public void loadAttachment(final Account account, final LocalMessage message, final Part part,
            final MessagingListener listener) {

        put("loadAttachment", listener, new Runnable() {
            @Override
            public void run() {
                LocalFolder localFolder = null;
                try {
                    String folderServerId = message.getFolder().getServerId();

                    LocalStore localStore = localStoreProvider.getInstance(account);
                    localFolder = localStore.getFolder(folderServerId);

                    ProgressBodyFactory bodyFactory = new ProgressBodyFactory(new ProgressListener() {
                        @Override
                        public void updateProgress(int progress) {
                            for (MessagingListener listener : getListeners()) {
                                listener.updateProgress(progress);
                            }
                        }
                    });

                    Backend backend = getBackend(account);
                    backend.fetchPart(folderServerId, message.getUid(), part, bodyFactory);

                    localFolder.addPartToMessage(message, part);

                    for (MessagingListener l : getListeners(listener)) {
                        l.loadAttachmentFinished(account, message, part);
                    }
                } catch (MessagingException me) {
                    Timber.v(me, "Exception loading attachment");

                    for (MessagingListener l : getListeners(listener)) {
                        l.loadAttachmentFailed(account, message, part, me.getMessage());
                    }
                    notifyUserIfCertificateProblem(account, me, true);
                } finally {
                    closeFolder(localFolder);
                }
            }
        });
    }

    /**
     * Stores the given message in the Outbox and starts a sendPendingMessages command to
     * attempt to send the message.
     */
    public void sendMessage(final Account account,
            final Message message,
            String plaintextSubject,
            MessagingListener listener) {
        try {
            LocalStore localStore = localStoreProvider.getInstance(account);
            LocalFolder localFolder = localStore.getFolder(account.getOutboxFolder());
            localFolder.open();
            localFolder.appendMessages(Collections.singletonList(message));
            LocalMessage localMessage = localFolder.getMessage(message.getUid());
            long messageId = localMessage.getDatabaseId();
            localMessage.setFlag(Flag.X_DOWNLOADED_FULL, true);
            if (plaintextSubject != null) {
                localMessage.setCachedDecryptedSubject(plaintextSubject);
            }
            localFolder.close();

            OutboxStateRepository outboxStateRepository = localStore.getOutboxStateRepository();
            outboxStateRepository.initializeOutboxState(messageId);

            sendPendingMessages(account, listener);
        } catch (Exception e) {
            /*
            for (MessagingListener l : getListeners())
            {
                // TODO general failed
            }
            */
            Timber.e(e, "Error sending message");

        }
    }

    public void sendMessageBlocking(Account account, Message message) throws MessagingException {
        Backend backend = getBackend(account);
        backend.sendMessage(message);
    }

    public void sendPendingMessages(MessagingListener listener) {
        final Preferences prefs = Preferences.getPreferences(context);
        for (Account account : prefs.getAvailableAccounts()) {
            sendPendingMessages(account, listener);
        }
    }


    /**
     * Attempt to send any messages that are sitting in the Outbox.
     */
    public void sendPendingMessages(final Account account,
            MessagingListener listener) {
        putBackground("sendPendingMessages", listener, new Runnable() {
            @Override
            public void run() {
                if (!account.isAvailable(context)) {
                    throw new UnavailableAccountException();
                }
                if (messagesPendingSend(account)) {

                    showSendingNotificationIfNecessary(account);

                    try {
                        sendPendingMessagesSynchronous(account);
                    } finally {
                        clearSendingNotificationIfNecessary(account);
                    }
                }
            }
        });
    }

    private void showSendingNotificationIfNecessary(Account account) {
        if (account.isNotifySync()) {
            notificationController.showSendingNotification(account);
        }
    }

    private void clearSendingNotificationIfNecessary(Account account) {
        if (account.isNotifySync()) {
            notificationController.clearSendingNotification(account);
        }
    }

    private boolean messagesPendingSend(final Account account) {
        LocalFolder localFolder = null;
        try {
            localFolder = localStoreProvider.getInstance(account).getFolder(
                    account.getOutboxFolder());
            if (!localFolder.exists()) {
                return false;
            }

            localFolder.open();

            if (localFolder.getMessageCount() > 0) {
                return true;
            }
        } catch (Exception e) {
            Timber.e(e, "Exception while checking for unsent messages");
        } finally {
            closeFolder(localFolder);
        }
        return false;
    }

    /**
     * Attempt to send any messages that are sitting in the Outbox.
     */
    @VisibleForTesting
    protected void sendPendingMessagesSynchronous(final Account account) {
        LocalFolder localFolder = null;
        Exception lastFailure = null;
        boolean wasPermanentFailure = false;
        try {
            LocalStore localStore = localStoreProvider.getInstance(account);
            OutboxStateRepository outboxStateRepository = localStore.getOutboxStateRepository();
            localFolder = localStore.getFolder(
                    account.getOutboxFolder());
            if (!localFolder.exists()) {
                Timber.v("Outbox does not exist");
                return;
            }

            localFolder.open();

            List<LocalMessage> localMessages = localFolder.getMessages(null);
            int progress = 0;
            int todo = localMessages.size();
            for (MessagingListener l : getListeners()) {
                l.synchronizeMailboxProgress(account, account.getSentFolder(), progress, todo);
            }
            /*
             * The profile we will use to pull all of the content
             * for a given local message into memory for sending.
             */
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            fp.add(FetchProfile.Item.BODY);

            Timber.i("Scanning folder '%s' (%d) for messages to send",
                    account.getOutboxFolder(), localFolder.getDatabaseId());

            Backend backend = getBackend(account);

            for (LocalMessage message : localMessages) {
                if (message.isSet(Flag.DELETED)) {
                    //FIXME: When uploading a message to the remote Sent folder the move code creates a placeholder
                    // message in the Outbox. This code gets rid of these messages. It'd be preferable if the
                    // placeholder message was never created, though.
                    message.destroy();
                    continue;
                }
                try {
                    long messageId = message.getDatabaseId();
                    OutboxState outboxState = outboxStateRepository.getOutboxState(messageId);

                    if (outboxState.getSendState() != SendState.READY) {
                        Timber.v("Skipping sending message " + message.getUid());
                        notificationController.showSendFailedNotification(account,
                                new MessagingException(message.getSubject()));
                        continue;
                    }

                    Timber.i("Send count for message %s is %d", message.getUid(),
                            outboxState.getNumberOfSendAttempts());

                    localFolder.fetch(Collections.singletonList(message), fp, null);
                    try {
                        if (message.getHeader(K9.IDENTITY_HEADER).length > 0 || message.isSet(Flag.DRAFT)) {
                            Timber.v("The user has set the Outbox and Drafts folder to the same thing. " +
                                    "This message appears to be a draft, so K-9 will not send it");
                            continue;
                        }

                        outboxStateRepository.incrementSendAttempts(messageId);
                        message.setFlag(Flag.X_SEND_IN_PROGRESS, true);

                        Timber.i("Sending message with UID %s", message.getUid());
                        backend.sendMessage(message);

                        message.setFlag(Flag.X_SEND_IN_PROGRESS, false);
                        message.setFlag(Flag.SEEN, true);
                        progress++;
                        for (MessagingListener l : getListeners()) {
                            l.synchronizeMailboxProgress(account, account.getSentFolder(), progress, todo);
                        }
                        moveOrDeleteSentMessage(account, localStore, localFolder, message);

                        outboxStateRepository.removeOutboxState(messageId);
                    } catch (AuthenticationFailedException e) {
                        outboxStateRepository.decrementSendAttempts(messageId);
                        lastFailure = e;
                        wasPermanentFailure = false;

                        handleAuthenticationFailure(account, false);
                        handleSendFailure(account, localFolder, message, e);
                    } catch (CertificateValidationException e) {
                        outboxStateRepository.decrementSendAttempts(messageId);
                        lastFailure = e;
                        wasPermanentFailure = false;

                        notifyUserIfCertificateProblem(account, e, false);
                        handleSendFailure(account, localFolder, message, e);
                    } catch (MessagingException e) {
                        lastFailure = e;
                        wasPermanentFailure = e.isPermanentFailure();

                        if (wasPermanentFailure) {
                            String errorMessage = e.getMessage();
                            outboxStateRepository.setSendAttemptError(messageId, errorMessage);
                        } else if (outboxState.getNumberOfSendAttempts() + 1 >= MAX_SEND_ATTEMPTS) {
                            outboxStateRepository.setSendAttemptsExceeded(messageId);
                        }

                        handleSendFailure(account, localFolder, message, e);
                    } catch (Exception e) {
                        lastFailure = e;
                        wasPermanentFailure = true;

                        handleSendFailure(account, localFolder, message, e);
                    }
                } catch (Exception e) {
                    lastFailure = e;
                    wasPermanentFailure = false;
                    Timber.e(e, "Failed to fetch message for sending");
                    notifySynchronizeMailboxFailed(account, localFolder, e);
                }
            }

            if (lastFailure != null) {
                if (wasPermanentFailure) {
                    notificationController.showSendFailedNotification(account, lastFailure);
                } else {
                    notificationController.showSendFailedNotification(account, lastFailure);
                }
            }
        } catch (UnavailableStorageException e) {
            Timber.i("Failed to send pending messages because storage is not available - trying again later.");
            throw new UnavailableAccountException(e);
        } catch (Exception e) {
            Timber.v(e, "Failed to send pending messages");
        } finally {
            if (lastFailure == null) {
                notificationController.clearSendFailedNotification(account);
            }
            closeFolder(localFolder);
        }
    }

    private void moveOrDeleteSentMessage(Account account, LocalStore localStore,
            LocalFolder localFolder, LocalMessage message) throws MessagingException {
        if (!account.hasSentFolder() || !account.isUploadSentMessages()) {
            Timber.i("Not uploading sent message; deleting local message");
            message.destroy();
        } else {
            LocalFolder localSentFolder = localStore.getFolder(account.getSentFolder());
            Timber.i("Moving sent message to folder '%s' (%d)", account.getSentFolder(), localSentFolder.getDatabaseId());

            localFolder.moveMessages(Collections.singletonList(message), localSentFolder);

            Timber.i("Moved sent message to folder '%s' (%d)", account.getSentFolder(), localSentFolder.getDatabaseId());

            PendingCommand command = PendingAppend.create(localSentFolder.getDatabaseId(), message.getUid());
            queuePendingCommand(account, command);
            processPendingCommands(account);
        }
    }

    private void handleSendFailure(Account account, LocalFolder localFolder, Message message, Exception exception)
            throws MessagingException {

        Timber.e(exception, "Failed to send message");
        message.setFlag(Flag.X_SEND_FAILED, true);

        notifySynchronizeMailboxFailed(account, localFolder, exception);
    }

    private void notifySynchronizeMailboxFailed(Account account, LocalFolder localFolder, Exception exception) {
        String folderServerId = localFolder.getServerId();
        String errorMessage = getRootCauseMessage(exception);
        for (MessagingListener listener : getListeners()) {
            listener.synchronizeMailboxFailed(account, folderServerId, errorMessage);
        }
    }

    public int getUnreadMessageCount(Account account) {
        return unreadMessageCountProvider.getUnreadMessageCount(account);
    }

    public int getUnreadMessageCount(SearchAccount searchAccount) {
        return unreadMessageCountProvider.getUnreadMessageCount(searchAccount);
    }

    public void getFolderUnreadMessageCount(final Account account, final String folderServerId,
            final MessagingListener l) {
        Runnable unreadRunnable = new Runnable() {
            @Override
            public void run() {

                int unreadMessageCount = 0;
                try {
                    unreadMessageCount = getFolderUnreadMessageCount(account, folderServerId);
                } catch (MessagingException me) {
                    Timber.e(me, "Count not get unread count for account %s", account.getDescription());
                }
                l.folderStatusChanged(account, folderServerId);
            }
        };


        put("getFolderUnread:" + account.getDescription() + ":" + folderServerId, l, unreadRunnable);
    }

    public int getFolderUnreadMessageCount(Account account, String folderServerId) throws MessagingException {
        LocalStore localStore = localStoreProvider.getInstance(account);
        LocalFolder localFolder = localStore.getFolder(folderServerId);
        return localFolder.getUnreadMessageCount();
    }

    public boolean isMoveCapable(MessageReference messageReference) {
        return !messageReference.getUid().startsWith(K9.LOCAL_UID_PREFIX);
    }

    public boolean isCopyCapable(MessageReference message) {
        return isMoveCapable(message);
    }

    public boolean isMoveCapable(final Account account) {
        return getBackend(account).getSupportsMove();
    }

    public boolean isCopyCapable(final Account account) {
        return getBackend(account).getSupportsCopy();
    }

    public boolean isPushCapable(Account account) {
        return getBackend(account).isPushCapable();
    }

    public boolean supportsSeenFlag(Account account) {
        return getBackend(account).getSupportsSeenFlag();
    }

    public boolean supportsExpunge(Account account) {
        return getBackend(account).getSupportsExpunge();
    }

    public boolean supportsSearchByDate(Account account) {
        return getBackend(account).getSupportsSearchByDate();
    }

    public boolean supportsUpload(Account account) {
        return getBackend(account).getSupportsUpload();
    }

    public void checkIncomingServerSettings(Account account) throws MessagingException {
        getBackend(account).checkIncomingServerSettings();
    }

    public void checkOutgoingServerSettings(Account account) throws MessagingException {
        getBackend(account).checkOutgoingServerSettings();
    }

    public void moveMessages(final Account srcAccount, final String srcFolder,
            List<MessageReference> messageReferences, final String destFolder) {
        actOnMessageGroup(srcAccount, srcFolder, messageReferences, new MessageActor() {
            @Override
            public void act(final Account account, LocalFolder messageFolder, final List<LocalMessage> messages) {
                suppressMessages(account, messages);

                putBackground("moveMessages", null, new Runnable() {
                    @Override
                    public void run() {
                        moveOrCopyMessageSynchronous(account, srcFolder, messages, destFolder, MoveOrCopyFlavor.MOVE);
                    }
                });
            }
        });
    }

    public void moveMessagesInThread(Account srcAccount, final String srcFolder,
            final List<MessageReference> messageReferences, final String destFolder) {
        actOnMessageGroup(srcAccount, srcFolder, messageReferences, new MessageActor() {
            @Override
            public void act(final Account account, LocalFolder messageFolder, final List<LocalMessage> messages) {
                suppressMessages(account, messages);

                putBackground("moveMessagesInThread", null, new Runnable() {
                    @Override
                    public void run() {
                        try {
                            List<LocalMessage> messagesInThreads = collectMessagesInThreads(account, messages);
                            moveOrCopyMessageSynchronous(account, srcFolder, messagesInThreads, destFolder,
                                    MoveOrCopyFlavor.MOVE);
                        } catch (MessagingException e) {
                            Timber.e(e, "Exception while moving messages");
                        }
                    }
                });
            }
        });
    }

    public void moveMessage(final Account account, final String srcFolder, final MessageReference message,
            final String destFolder) {
        moveMessages(account, srcFolder, Collections.singletonList(message), destFolder);
    }

    public void copyMessages(final Account srcAccount, final String srcFolder,
            final List<MessageReference> messageReferences, final String destFolder) {
        actOnMessageGroup(srcAccount, srcFolder, messageReferences, new MessageActor() {
            @Override
            public void act(final Account account, LocalFolder messageFolder, final List<LocalMessage> messages) {
                putBackground("copyMessages", null, new Runnable() {
                    @Override
                    public void run() {
                        moveOrCopyMessageSynchronous(srcAccount, srcFolder, messages, destFolder,
                                MoveOrCopyFlavor.COPY);
                    }
                });
            }
        });
    }

    public void copyMessagesInThread(Account srcAccount, final String srcFolder,
            final List<MessageReference> messageReferences, final String destFolder) {
        actOnMessageGroup(srcAccount, srcFolder, messageReferences, new MessageActor() {
            @Override
            public void act(final Account account, LocalFolder messageFolder, final List<LocalMessage> messages) {
                putBackground("copyMessagesInThread", null, new Runnable() {
                    @Override
                    public void run() {
                        try {
                            List<LocalMessage> messagesInThreads = collectMessagesInThreads(account, messages);
                            moveOrCopyMessageSynchronous(account, srcFolder, messagesInThreads, destFolder,
                                    MoveOrCopyFlavor.COPY);
                        } catch (MessagingException e) {
                            Timber.e(e, "Exception while copying messages");
                        }
                    }
                });
            }
        });
    }

    public void copyMessage(final Account account, final String srcFolder, final MessageReference message,
            final String destFolder) {

        copyMessages(account, srcFolder, Collections.singletonList(message), destFolder);
    }

    private void moveOrCopyMessageSynchronous(final Account account, final String srcFolder,
            final List<LocalMessage> inMessages, final String destFolder,
            MoveOrCopyFlavor operation) {

        try {
            LocalStore localStore = localStoreProvider.getInstance(account);
            if ((operation == MoveOrCopyFlavor.MOVE
                        || operation == MoveOrCopyFlavor.MOVE_AND_MARK_AS_READ)
                    && !isMoveCapable(account)) {
                return;
            }
            if (operation == MoveOrCopyFlavor.COPY && !isCopyCapable(account)) {
                return;
            }

            LocalFolder localSrcFolder = localStore.getFolder(srcFolder);
            localSrcFolder.open();

            LocalFolder localDestFolder = localStore.getFolder(destFolder);
            localDestFolder.open();

            boolean unreadCountAffected = false;
            List<String> uids = new LinkedList<>();
            for (Message message : inMessages) {
                String uid = message.getUid();
                if (!uid.startsWith(K9.LOCAL_UID_PREFIX)) {
                    uids.add(uid);
                }

                if (!unreadCountAffected && !message.isSet(Flag.SEEN)) {
                    unreadCountAffected = true;
                }
            }

            List<LocalMessage> messages = localSrcFolder.getMessagesByUids(uids);
            if (messages.size() > 0) {
                Map<String, Message> origUidMap = new HashMap<>();

                for (Message message : messages) {
                    origUidMap.put(message.getUid(), message);
                }

                Timber.i("moveOrCopyMessageSynchronous: source folder = %s, %d messages, destination folder = %s, " +
                        "operation = %s", srcFolder, messages.size(), destFolder, operation.name());

                Map<String, String> uidMap;

                if (operation == MoveOrCopyFlavor.COPY) {
                    FetchProfile fp = new FetchProfile();
                    fp.add(Item.ENVELOPE);
                    fp.add(Item.BODY);
                    localSrcFolder.fetch(messages, fp, null);
                    uidMap = localSrcFolder.copyMessages(messages, localDestFolder);

                    if (unreadCountAffected) {
                        // If this copy operation changes the unread count in the destination
                        // folder, notify the listeners.
                        for (MessagingListener l : getListeners()) {
                            l.folderStatusChanged(account, destFolder);
                        }
                    }
                } else {
                    uidMap = localSrcFolder.moveMessages(messages, localDestFolder);
                    for (Entry<String, Message> entry : origUidMap.entrySet()) {
                        String origUid = entry.getKey();
                        Message message = entry.getValue();
                        for (MessagingListener l : getListeners()) {
                            l.messageUidChanged(account, srcFolder, origUid, message.getUid());
                        }
                    }
                    if (operation == MoveOrCopyFlavor.MOVE_AND_MARK_AS_READ) {
                        localDestFolder.setFlags(messages, Collections.singleton(Flag.SEEN), true);
                        unreadCountAffected = true;
                    }
                    unsuppressMessages(account, messages);

                    if (unreadCountAffected) {
                        // If this move operation changes the unread count, notify the listeners
                        // that the unread count changed in both the source and destination folder.
                        int unreadMessageCountSrc = localSrcFolder.getUnreadMessageCount();
                        int unreadMessageCountDest = localDestFolder.getUnreadMessageCount();
                        for (MessagingListener l : getListeners()) {
                            l.folderStatusChanged(account, srcFolder);
                            l.folderStatusChanged(account, destFolder);
                        }
                    }
                }

                queueMoveOrCopy(account, localSrcFolder.getDatabaseId(), localDestFolder.getDatabaseId(),
                        operation, uidMap);
            }

            processPendingCommands(account);
        } catch (UnavailableStorageException e) {
            Timber.i("Failed to move/copy message because storage is not available - trying again later.");
            throw new UnavailableAccountException(e);
        } catch (MessagingException me) {
            throw new RuntimeException("Error moving message", me);
        }
    }

    public void expunge(Account account, long folderId) {
        putBackground("expunge", null, () -> {
            queueExpunge(account, folderId);
            processPendingCommands(account);
        });
    }

    public void deleteDraft(final Account account, long id) {
        LocalFolder localFolder = null;
        try {
            LocalStore localStore = localStoreProvider.getInstance(account);
            localFolder = localStore.getFolder(account.getDraftsFolder());
            localFolder.open();
            String uid = localFolder.getMessageUidById(id);
            if (uid != null) {
                MessageReference messageReference = new MessageReference(
                        account.getUuid(), account.getDraftsFolder(), uid, null);
                deleteMessage(messageReference, null);
            }
        } catch (MessagingException me) {
            Timber.e(me, "Error deleting draft");
        } finally {
            closeFolder(localFolder);
        }
    }

    public void deleteThreads(final List<MessageReference> messages) {
        actOnMessagesGroupedByAccountAndFolder(messages, new MessageActor() {
            @Override
            public void act(final Account account, final LocalFolder messageFolder,
                    final List<LocalMessage> accountMessages) {
                suppressMessages(account, accountMessages);

                putBackground("deleteThreads", null, new Runnable() {
                    @Override
                    public void run() {
                        deleteThreadsSynchronous(account, messageFolder.getServerId(), accountMessages);
                    }
                });
            }
        });
    }

    private void deleteThreadsSynchronous(Account account, String folderServerId, List<LocalMessage> messages) {
        try {
            List<LocalMessage> messagesToDelete = collectMessagesInThreads(account, messages);

            deleteMessagesSynchronous(account, folderServerId,
                    messagesToDelete, null);
        } catch (MessagingException e) {
            Timber.e(e, "Something went wrong while deleting threads");
        }
    }

    private List<LocalMessage> collectMessagesInThreads(Account account, List<LocalMessage> messages)
            throws MessagingException {

        LocalStore localStore = localStoreProvider.getInstance(account);

        List<LocalMessage> messagesInThreads = new ArrayList<>();
        for (LocalMessage message : messages) {
            long rootId = message.getRootId();
            long threadId = (rootId == -1) ? message.getThreadId() : rootId;

            List<LocalMessage> messagesInThread = localStore.getMessagesInThread(threadId);

            messagesInThreads.addAll(messagesInThread);
        }

        return messagesInThreads;
    }

    public void deleteMessage(MessageReference message, final MessagingListener listener) {
        deleteMessages(Collections.singletonList(message), listener);
    }

    public void deleteMessages(List<MessageReference> messages, final MessagingListener listener) {
        actOnMessagesGroupedByAccountAndFolder(messages, new MessageActor() {

            @Override
            public void act(final Account account, final LocalFolder messageFolder,
                    final List<LocalMessage> accountMessages) {
                suppressMessages(account, accountMessages);

                putBackground("deleteMessages", null, new Runnable() {
                    @Override
                    public void run() {
                        deleteMessagesSynchronous(account, messageFolder.getServerId(), accountMessages, listener);
                    }
                });
            }

        });
    }

    @SuppressLint("NewApi") // used for debugging only
    public void debugClearMessagesLocally(final List<MessageReference> messages) {
        if (!K9.DEVELOPER_MODE) {
            throw new AssertionError("method must only be used in developer mode!");
        }

        actOnMessagesGroupedByAccountAndFolder(messages, new MessageActor() {

            @Override
            public void act(final Account account, final LocalFolder messageFolder,
                    final List<LocalMessage> accountMessages) {

                putBackground("debugClearLocalMessages", null, new Runnable() {
                    @Override
                    public void run() {
                        for (LocalMessage message : accountMessages) {
                            try {
                                message.debugClearLocalData();
                            } catch (MessagingException e) {
                                throw new AssertionError("clearing local message content failed!", e);
                            }
                        }
                    }
                });
            }
        });

    }

    private void deleteMessagesSynchronous(final Account account, final String folder,
            final List<LocalMessage> messages,
            MessagingListener listener) {
        LocalFolder localFolder = null;
        LocalFolder localTrashFolder = null;
        try {
            List<LocalMessage> localOnlyMessages = new ArrayList<>();
            List<LocalMessage> syncedMessages = new ArrayList<>();
            List<String> syncedMessageUids = new ArrayList<>();
            for (LocalMessage message : messages) {
                String uid = message.getUid();
                if (uid.startsWith(K9.LOCAL_UID_PREFIX)) {
                    localOnlyMessages.add(message);
                } else {
                    syncedMessages.add(message);
                    syncedMessageUids.add(uid);
                }
            }

            Backend backend = getBackend(account);

            LocalStore localStore = localStoreProvider.getInstance(account);
            localFolder = localStore.getFolder(folder);
            localFolder.open();

            Map<String, String> uidMap = null;
            if (folder.equals(account.getTrashFolder()) || !account.hasTrashFolder() ||
                    (backend.getSupportsTrashFolder() && !backend.isDeleteMoveToTrash())) {
                Timber.d("Not moving deleted messages to local Trash folder. Removing local copies.");

                if (!localOnlyMessages.isEmpty()) {
                    localFolder.destroyMessages(localOnlyMessages);
                }
                if (!syncedMessages.isEmpty()) {
                    localFolder.setFlags(syncedMessages, Collections.singleton(Flag.DELETED), true);
                }
            } else {
                Timber.d("Deleting messages in normal folder, moving");
                localTrashFolder = localStore.getFolder(account.getTrashFolder());
                uidMap = localFolder.moveMessages(messages, localTrashFolder);

                if (account.isMarkMessageAsReadOnDelete()) {
                    localTrashFolder.setFlags(messages, Collections.singleton(Flag.SEEN), true);
                }
            }

            for (MessagingListener l : getListeners()) {
                l.folderStatusChanged(account, folder);
                if (localTrashFolder != null) {
                    l.folderStatusChanged(account, account.getTrashFolder());
                }
            }

            Timber.d("Delete policy for account %s is %s", account.getDescription(), account.getDeletePolicy());

            if (folder.equals(account.getOutboxFolder())) {
                for (Message message : messages) {
                    // If the message was in the Outbox, then it has been copied to local Trash, and has
                    // to be copied to remote trash
                    long trashFolderId = getFolderId(account, account.getTrashFolder());
                    PendingCommand command = PendingAppend.create(trashFolderId, message.getUid());
                    queuePendingCommand(account, command);
                }
                processPendingCommands(account);
            } else if (!syncedMessageUids.isEmpty()) {
                if (account.getDeletePolicy() == DeletePolicy.ON_DELETE) {
                    long folderId = localFolder.getDatabaseId();
                    if (!account.hasTrashFolder() || folder.equals(account.getTrashFolder()) ||
                            !backend.isDeleteMoveToTrash()) {
                        queueDelete(account, folderId, syncedMessageUids);
                    } else if (account.isMarkMessageAsReadOnDelete()) {
                        long trashFolderId = getFolderId(account, account.getTrashFolder());
                        queueMoveOrCopy(account, folderId, trashFolderId,
                                MoveOrCopyFlavor.MOVE_AND_MARK_AS_READ, uidMap);
                    } else {
                        long trashFolderId = getFolderId(account, account.getTrashFolder());
                        queueMoveOrCopy(account, folderId, trashFolderId,
                                MoveOrCopyFlavor.MOVE, uidMap);
                    }
                    processPendingCommands(account);
                } else if (account.getDeletePolicy() == DeletePolicy.MARK_AS_READ) {
                    queueSetFlag(account, localFolder.getDatabaseId(), true, Flag.SEEN, syncedMessageUids);
                    processPendingCommands(account);
                } else {
                    Timber.d("Delete policy %s prevents delete from server", account.getDeletePolicy());
                }
            }

            unsuppressMessages(account, messages);
        } catch (UnavailableStorageException e) {
            Timber.i("Failed to delete message because storage is not available - trying again later.");
            throw new UnavailableAccountException(e);
        } catch (MessagingException me) {
            throw new RuntimeException("Error deleting message from local store.", me);
        } finally {
            closeFolder(localFolder);
            closeFolder(localTrashFolder);
        }
    }

    private static List<String> getUidsFromMessages(List<LocalMessage> messages) {
        List<String> uids = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            uids.add(messages.get(i).getUid());
        }
        return uids;
    }

    void processPendingEmptyTrash(Account account) throws MessagingException {
        if (!account.hasTrashFolder()) {
            return;
        }

        Backend backend = getBackend(account);

        String trashFolderServerId = account.getTrashFolder();
        backend.deleteAllMessages(trashFolderServerId);

        if (account.getExpungePolicy() == Expunge.EXPUNGE_IMMEDIATELY && backend.getSupportsExpunge()) {
            backend.expunge(trashFolderServerId);
        }

        // Remove all messages marked as deleted
        LocalStore localStore = localStoreProvider.getInstance(account);
        LocalFolder folder = localStore.getFolder(trashFolderServerId);
        folder.open();
        folder.destroyDeletedMessages();

        compact(account, null);
    }

    public void emptyTrash(final Account account, MessagingListener listener) {
        putBackground("emptyTrash", listener, new Runnable() {
            @Override
            public void run() {
                LocalFolder localFolder = null;
                try {
                    LocalStore localStore = localStoreProvider.getInstance(account);
                    String trashFolderServerId = account.getTrashFolder();
                    localFolder = localStore.getFolder(trashFolderServerId);
                    localFolder.open();

                    boolean isTrashLocalOnly = isTrashLocalOnly(account);
                    if (isTrashLocalOnly) {
                        localFolder.clearAllMessages();
                    } else {
                        localFolder.destroyLocalOnlyMessages();
                        localFolder.setFlags(Collections.singleton(Flag.DELETED), true);
                    }

                    for (MessagingListener l : getListeners()) {
                        l.folderStatusChanged(account, trashFolderServerId);
                    }

                    if (!isTrashLocalOnly) {
                        PendingCommand command = PendingEmptyTrash.create();
                        queuePendingCommand(account, command);
                        processPendingCommands(account);
                    }
                } catch (UnavailableStorageException e) {
                    Timber.i("Failed to empty trash because storage is not available - trying again later.");
                    throw new UnavailableAccountException(e);
                } catch (Exception e) {
                    Timber.e(e, "emptyTrash failed");
                } finally {
                    closeFolder(localFolder);
                }
            }
        });
    }

    public void clearFolder(final Account account, final String folderServerId, final MessagingListener listener) {
        putBackground("clearFolder", listener, new Runnable() {
            @Override
            public void run() {
                clearFolderSynchronous(account, folderServerId, listener);
            }
        });
    }

    @VisibleForTesting
    protected void clearFolderSynchronous(Account account, String folderServerId, MessagingListener listener) {
        LocalFolder localFolder = null;
        try {
            localFolder = localStoreProvider.getInstance(account).getFolder(folderServerId);
            localFolder.open();
            localFolder.clearAllMessages();
        } catch (UnavailableStorageException e) {
            Timber.i("Failed to clear folder because storage is not available - trying again later.");
            throw new UnavailableAccountException(e);
        } catch (Exception e) {
            Timber.e(e, "clearFolder failed");
        } finally {
            closeFolder(localFolder);
        }
    }


    /**
     * Find out whether the account type only supports a local Trash folder.
     * <p>
     * <p>Note: Currently this is only the case for POP3 accounts.</p>
     *
     * @param account
     *         The account to check.
     *
     * @return {@code true} if the account only has a local Trash folder that is not synchronized
     * with a folder on the server. {@code false} otherwise.
     */
    private boolean isTrashLocalOnly(Account account) {
        Backend backend = getBackend(account);
        return !backend.getSupportsTrashFolder();
    }

    public void sendAlternate(Context context, Account account, LocalMessage message) {
        Timber.d("Got message %s:%s:%s for sendAlternate",
                account.getDescription(), message.getFolder(), message.getUid());

        Intent msg = new Intent(Intent.ACTION_SEND);
        String quotedText = null;
        Part part = MimeUtility.findFirstPartByMimeType(message, "text/plain");
        if (part == null) {
            part = MimeUtility.findFirstPartByMimeType(message, "text/html");
        }
        if (part != null) {
            quotedText = MessageExtractor.getTextFromPart(part);
        }
        if (quotedText != null) {
            msg.putExtra(Intent.EXTRA_TEXT, quotedText);
        }
        msg.putExtra(Intent.EXTRA_SUBJECT, message.getSubject());

        Address[] from = message.getFrom();
        String[] senders = new String[from.length];
        for (int i = 0; i < from.length; i++) {
            senders[i] = from[i].toString();
        }
        msg.putExtra(Intents.Share.EXTRA_FROM, senders);

        Address[] to = message.getRecipients(RecipientType.TO);
        String[] recipientsTo = new String[to.length];
        for (int i = 0; i < to.length; i++) {
            recipientsTo[i] = to[i].toString();
        }
        msg.putExtra(Intent.EXTRA_EMAIL, recipientsTo);

        Address[] cc = message.getRecipients(RecipientType.CC);
        String[] recipientsCc = new String[cc.length];
        for (int i = 0; i < cc.length; i++) {
            recipientsCc[i] = cc[i].toString();
        }
        msg.putExtra(Intent.EXTRA_CC, recipientsCc);

        msg.setType("text/plain");
        Intent chooserIntent = Intent.createChooser(msg, resourceProvider.sendAlternateChooserTitle());
        context.startActivity(chooserIntent);
    }

    public void checkMailBlocking(Account account) {
        final CountDownLatch latch = new CountDownLatch(1);
        checkMail(context, account, true, false, new SimpleMessagingListener() {
            @Override
            public void checkMailFinished(Context context, Account account) {
                latch.countDown();
            }
        });

        Timber.v("checkMailBlocking(%s) about to await latch release", account.getDescription());

        try {
            latch.await();
            Timber.v("checkMailBlocking(%s) got latch release", account.getDescription());
        } catch (Exception e) {
            Timber.e(e, "Interrupted while awaiting latch release");
        }
    }

    /**
     * Checks mail for one or multiple accounts. If account is null all accounts
     * are checked.
     */
    public void checkMail(final Context context, final Account account,
            final boolean ignoreLastCheckedTime,
            final boolean useManualWakeLock,
            final MessagingListener listener) {

        TracingWakeLock twakeLock = null;
        if (useManualWakeLock) {
            TracingPowerManager pm = TracingPowerManager.getPowerManager(context);

            twakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "K9 MessagingController.checkMail");
            twakeLock.setReferenceCounted(false);
            twakeLock.acquire(K9.MANUAL_WAKE_LOCK_TIMEOUT);
        }
        final TracingWakeLock wakeLock = twakeLock;

        for (MessagingListener l : getListeners(listener)) {
            l.checkMailStarted(context, account);
        }
        putBackground("checkMail", listener, new Runnable() {
            @Override
            public void run() {

                try {
                    Timber.i("Starting mail check");

                    Preferences prefs = Preferences.getPreferences(context);

                    Collection<Account> accounts;
                    if (account != null) {
                        accounts = new ArrayList<>(1);
                        accounts.add(account);
                    } else {
                        accounts = prefs.getAvailableAccounts();
                    }

                    for (final Account account : accounts) {
                        checkMailForAccount(context, account, ignoreLastCheckedTime, listener);
                    }

                } catch (Exception e) {
                    Timber.e(e, "Unable to synchronize mail");
                }
                putBackground("finalize sync", null, new Runnable() {
                            @Override
                            public void run() {

                                Timber.i("Finished mail sync");

                                if (wakeLock != null) {
                                    wakeLock.release();
                                }
                                for (MessagingListener l : getListeners(listener)) {
                                    l.checkMailFinished(context, account);
                                }

                            }
                        }
                );
            }
        });
    }


    private void checkMailForAccount(final Context context, final Account account,
            final boolean ignoreLastCheckedTime,
            final MessagingListener listener) {
        if (!account.isAvailable(context)) {
            Timber.i("Skipping synchronizing unavailable account %s", account.getDescription());
            return;
        }
        final long accountInterval = account.getAutomaticCheckIntervalMinutes() * 60 * 1000;
        if (!ignoreLastCheckedTime && accountInterval <= 0) {
            Timber.i("Skipping synchronizing account %s", account.getDescription());
            return;
        }

        Timber.i("Synchronizing account %s", account.getDescription());

        account.setRingNotified(false);

        sendPendingMessages(account, listener);

        try {
            Account.FolderMode aDisplayMode = account.getFolderDisplayMode();
            Account.FolderMode aSyncMode = account.getFolderSyncMode();

            LocalStore localStore = localStoreProvider.getInstance(account);
            for (final LocalFolder folder : localStore.getPersonalNamespaces(false)) {
                folder.open();

                FolderClass fDisplayClass = folder.getDisplayClass();
                FolderClass fSyncClass = folder.getSyncClass();

                if (LocalFolder.isModeMismatch(aDisplayMode, fDisplayClass)) {
                    // Never sync a folder that isn't displayed
                    /*
                    if (K9.DEBUG) {
                        Log.v(K9.LOG_TAG, "Not syncing folder " + folder.getName() +
                              " which is in display mode " + fDisplayClass + " while account is in display mode " + aDisplayMode);
                    }
                    */

                    continue;
                }

                if (LocalFolder.isModeMismatch(aSyncMode, fSyncClass)) {
                    // Do not sync folders in the wrong class
                    /*
                    if (K9.DEBUG) {
                        Log.v(K9.LOG_TAG, "Not syncing folder " + folder.getName() +
                              " which is in sync mode " + fSyncClass + " while account is in sync mode " + aSyncMode);
                    }
                    */

                    continue;
                }
                synchronizeFolder(account, folder, ignoreLastCheckedTime, accountInterval, listener);
            }
        } catch (MessagingException e) {
            Timber.e(e, "Unable to synchronize account %s", account.getName());
        } finally {
            putBackground("clear notification flag for " + account.getDescription(), null, new Runnable() {
                        @Override
                        public void run() {
                            Timber.v("Clearing notification flag for %s", account.getDescription());

                            account.setRingNotified(false);
                            if (getUnreadMessageCount(account) == 0) {
                                notificationController.clearNewMailNotifications(account);
                            }
                        }
                    }
            );
        }


    }


    private void synchronizeFolder(
            final Account account,
            final LocalFolder folder,
            final boolean ignoreLastCheckedTime,
            final long accountInterval,
            final MessagingListener listener) {

        Timber.v("Folder %s was last synced @ %tc", folder.getServerId(), folder.getLastChecked());

        if (!ignoreLastCheckedTime && folder.getLastChecked() > System.currentTimeMillis() - accountInterval) {
            Timber.v("Not syncing folder %s, previously synced @ %tc which would be too recent for the account " +
                    "period", folder.getServerId(), folder.getLastChecked());
            return;
        }

        putBackground("sync" + folder.getServerId(), null, new Runnable() {
                    @Override
                    public void run() {
                        LocalFolder tLocalFolder = null;
                        try {
                            // In case multiple Commands get enqueued, don't run more than
                            // once
                            final LocalStore localStore = localStoreProvider.getInstance(account);
                            tLocalFolder = localStore.getFolder(folder.getServerId());
                            tLocalFolder.open();

                            if (!ignoreLastCheckedTime && tLocalFolder.getLastChecked() >
                                    (System.currentTimeMillis() - accountInterval)) {
                                Timber.v("Not running Command for folder %s, previously synced @ %tc which would " +
                                        "be too recent for the account period",
                                        folder.getServerId(), folder.getLastChecked());
                                return;
                            }
                            showFetchingMailNotificationIfNecessary(account, folder);
                            try {
                                synchronizeMailboxSynchronous(account, folder.getServerId(), listener);
                            } finally {
                                clearFetchingMailNotificationIfNecessary(account);
                            }
                        } catch (Exception e) {
                            Timber.e(e, "Exception while processing folder %s:%s",
                                    account.getDescription(), folder.getServerId());
                        } finally {
                            closeFolder(tLocalFolder);
                        }
                    }
                }
        );


    }

    private void showFetchingMailNotificationIfNecessary(Account account, LocalFolder folder) {
        if (account.isNotifySync()) {
            notificationController.showFetchingMailNotification(account, folder);
        }
    }

    private void clearFetchingMailNotificationIfNecessary(Account account) {
        if (account.isNotifySync()) {
            notificationController.clearFetchingMailNotification(account);
        }
    }


    public void compact(final Account account, final MessagingListener ml) {
        putBackground("compact:" + account.getDescription(), ml, new Runnable() {
            @Override
            public void run() {
                try {
                    LocalStore localStore = localStoreProvider.getInstance(account);
                    long oldSize = localStore.getSize();
                    localStore.compact();
                    long newSize = localStore.getSize();
                    for (MessagingListener l : getListeners(ml)) {
                        l.accountSizeChanged(account, oldSize, newSize);
                    }
                } catch (UnavailableStorageException e) {
                    Timber.i("Failed to compact account because storage is not available - trying again later.");
                    throw new UnavailableAccountException(e);
                } catch (Exception e) {
                    Timber.e(e, "Failed to compact account %s", account.getDescription());
                }
            }
        });
    }

    public void clear(final Account account, final MessagingListener ml) {
        putBackground("clear:" + account.getDescription(), ml, new Runnable() {
            @Override
            public void run() {
                try {
                    LocalStore localStore = localStoreProvider.getInstance(account);
                    long oldSize = localStore.getSize();
                    localStore.clear();
                    localStore.resetVisibleLimits(account.getDisplayCount());
                    long newSize = localStore.getSize();
                    for (MessagingListener l : getListeners(ml)) {
                        l.accountSizeChanged(account, oldSize, newSize);
                    }
                } catch (UnavailableStorageException e) {
                    Timber.i("Failed to clear account because storage is not available - trying again later.");
                    throw new UnavailableAccountException(e);
                } catch (Exception e) {
                    Timber.e(e, "Failed to clear account %s", account.getDescription());
                }
            }
        });
    }

    public void recreate(final Account account, final MessagingListener ml) {
        putBackground("recreate:" + account.getDescription(), ml, new Runnable() {
            @Override
            public void run() {
                try {
                    LocalStore localStore = localStoreProvider.getInstance(account);
                    long oldSize = localStore.getSize();
                    localStore.recreate();
                    localStore.resetVisibleLimits(account.getDisplayCount());
                    long newSize = localStore.getSize();
                    for (MessagingListener l : getListeners(ml)) {
                        l.accountSizeChanged(account, oldSize, newSize);
                    }
                } catch (UnavailableStorageException e) {
                    Timber.i("Failed to recreate an account because storage is not available - trying again later.");
                    throw new UnavailableAccountException(e);
                } catch (Exception e) {
                    Timber.e(e, "Failed to recreate account %s", account.getDescription());
                }
            }
        });
    }

    public void deleteAccount(Account account) {
        notificationController.clearNewMailNotifications(account);
        memorizingMessagingListener.removeAccount(account);
    }

    /**
     * Save a draft message.
     *
     * @param account
     *         Account we are saving for.
     * @param message
     *         Message to save.
     *
     * @return Message representing the entry in the local store.
     */
    public Message saveDraft(final Account account, final Message message, long existingDraftId, String plaintextSubject, boolean saveRemotely) {
        LocalMessage localMessage = null;
        try {
            LocalStore localStore = localStoreProvider.getInstance(account);
            LocalFolder localFolder = localStore.getFolder(account.getDraftsFolder());
            localFolder.open();

            if (existingDraftId != INVALID_MESSAGE_ID) {
                String uid = localFolder.getMessageUidById(existingDraftId);
                message.setUid(uid);
            }

            // Save the message to the store.
            localFolder.appendMessages(Collections.singletonList(message));
            // Fetch the message back from the store.  This is the Message that's returned to the caller.
            localMessage = localFolder.getMessage(message.getUid());
            localMessage.setFlag(Flag.X_DOWNLOADED_FULL, true);
            if (plaintextSubject != null) {
                localMessage.setCachedDecryptedSubject(plaintextSubject);
            }

            if (saveRemotely) {
                PendingCommand command = PendingAppend.create(localFolder.getDatabaseId(), localMessage.getUid());
                queuePendingCommand(account, command);
                processPendingCommands(account);
            }

        } catch (MessagingException e) {
            Timber.e(e, "Unable to save message as draft.");
        }
        return localMessage;
    }

    public long getId(Message message) {
        long id;
        if (message instanceof LocalMessage) {
            id = ((LocalMessage) message).getDatabaseId();
        } else {
            Timber.w("MessagingController.getId() called without a LocalMessage");
            id = INVALID_MESSAGE_ID;
        }

        return id;
    }

    private static AtomicInteger sequencing = new AtomicInteger(0);

    private static class Command implements Comparable<Command> {
        public Runnable runnable;
        public MessagingListener listener;
        public String description;
        boolean isForegroundPriority;

        int sequence = sequencing.getAndIncrement();

        @Override
        public int compareTo(@NonNull Command other) {
            if (other.isForegroundPriority && !isForegroundPriority) {
                return 1;
            } else if (!other.isForegroundPriority && isForegroundPriority) {
                return -1;
            } else {
                return (sequence - other.sequence);
            }
        }
    }

    public MessagingListener getCheckMailListener() {
        return checkMailListener;
    }

    public void setCheckMailListener(MessagingListener checkMailListener) {
        if (this.checkMailListener != null) {
            removeListener(this.checkMailListener);
        }
        this.checkMailListener = checkMailListener;
        if (this.checkMailListener != null) {
            addListener(this.checkMailListener);
        }
    }

    public void cancelNotificationsForAccount(Account account) {
        notificationController.clearNewMailNotifications(account);
    }

    public void cancelNotificationForMessage(Account account, MessageReference messageReference) {
        notificationController.removeNewMailNotification(account, messageReference);
    }

    public void clearCertificateErrorNotifications(Account account, boolean incoming) {
        notificationController.clearCertificateErrorNotifications(account, incoming);
    }

    public void notifyUserIfCertificateProblem(Account account, Exception exception, boolean incoming) {
        if (!(exception instanceof CertificateValidationException)) {
            return;
        }

        CertificateValidationException cve = (CertificateValidationException) exception;
        if (!cve.needsUserAttention()) {
            return;
        }

        notificationController.showCertificateErrorNotification(account, incoming);
    }

    private void actOnMessagesGroupedByAccountAndFolder(List<MessageReference> messages, MessageActor actor) {
        Map<String, Map<String, List<MessageReference>>> accountMap = groupMessagesByAccountAndFolder(messages);

        for (Map.Entry<String, Map<String, List<MessageReference>>> entry : accountMap.entrySet()) {
            String accountUuid = entry.getKey();
            Account account = Preferences.getPreferences(context).getAccount(accountUuid);

            Map<String, List<MessageReference>> folderMap = entry.getValue();
            for (Map.Entry<String, List<MessageReference>> folderEntry : folderMap.entrySet()) {
                String folderServerId = folderEntry.getKey();
                List<MessageReference> messageList = folderEntry.getValue();
                actOnMessageGroup(account, folderServerId, messageList, actor);
            }
        }
    }

    @NonNull
    private Map<String, Map<String, List<MessageReference>>> groupMessagesByAccountAndFolder(
            List<MessageReference> messages) {
        Map<String, Map<String, List<MessageReference>>> accountMap = new HashMap<>();

        for (MessageReference message : messages) {
            if (message == null) {
                continue;
            }
            String accountUuid = message.getAccountUuid();
            String folderServerId = message.getFolderServerId();

            Map<String, List<MessageReference>> folderMap = accountMap.get(accountUuid);
            if (folderMap == null) {
                folderMap = new HashMap<>();
                accountMap.put(accountUuid, folderMap);
            }
            List<MessageReference> messageList = folderMap.get(folderServerId);
            if (messageList == null) {
                messageList = new LinkedList<>();
                folderMap.put(folderServerId, messageList);
            }

            messageList.add(message);
        }
        return accountMap;
    }

    private void actOnMessageGroup(
            Account account, String folderServerId, List<MessageReference> messageReferences, MessageActor actor) {
        try {
            LocalFolder messageFolder = localStoreProvider.getInstance(account).getFolder(folderServerId);
            List<LocalMessage> localMessages = messageFolder.getMessagesByReference(messageReferences);
            actor.act(account, messageFolder, localMessages);
        } catch (MessagingException e) {
            Timber.e(e, "Error loading account?!");
        }

    }

    private interface MessageActor {
        void act(Account account, LocalFolder messageFolder, List<LocalMessage> messages);
    }

    class ControllerSyncListener implements SyncListener {
        private final Account account;
        private final MessagingListener listener;
        private final LocalStore localStore;
        private final int previousUnreadMessageCount;
        boolean syncFailed = false;


        ControllerSyncListener(Account account, MessagingListener listener) {
            this.account = account;
            this.listener = listener;
            this.localStore = getLocalStoreOrThrow(account);

            previousUnreadMessageCount = getUnreadMessageCount(account);
        }

        @Override
        public void syncStarted(@NotNull String folderServerId) {
            for (MessagingListener messagingListener : getListeners(listener)) {
                messagingListener.synchronizeMailboxStarted(account, folderServerId);
            }
        }

        @Override
        public void syncAuthenticationSuccess() {
            notificationController.clearAuthenticationErrorNotification(account, true);
        }

        @Override
        public void syncHeadersStarted(@NotNull String folderServerId) {
            for (MessagingListener messagingListener : getListeners(listener)) {
                messagingListener.synchronizeMailboxHeadersStarted(account, folderServerId);
            }
        }

        @Override
        public void syncHeadersProgress(@NotNull String folderServerId, int completed, int total) {
            for (MessagingListener messagingListener : getListeners(listener)) {
                messagingListener.synchronizeMailboxHeadersProgress(account, folderServerId, completed, total);
            }
        }

        @Override
        public void syncHeadersFinished(@NotNull String folderServerId, int totalMessagesInMailbox,
                int numNewMessages) {
            for (MessagingListener messagingListener : getListeners(listener)) {
                messagingListener.synchronizeMailboxHeadersFinished(account, folderServerId, totalMessagesInMailbox,
                        numNewMessages);
            }
        }

        @Override
        public void syncProgress(@NotNull String folderServerId, int completed, int total) {
            for (MessagingListener messagingListener : getListeners(listener)) {
                messagingListener.synchronizeMailboxProgress(account, folderServerId, completed, total);
            }
        }

        @Override
        public void syncNewMessage(@NotNull String folderServerId, @NotNull String messageServerId,
                boolean isOldMessage) {

            // Send a notification of this message
            LocalMessage message = loadMessage(folderServerId, messageServerId);
            LocalFolder localFolder = message.getFolder();
            if (notificationStrategy.shouldNotifyForMessage(account, localFolder, message, isOldMessage)) {
                // Notify with the localMessage so that we don't have to recalculate the content preview.
                notificationController.addNewMailNotification(account, message, previousUnreadMessageCount);
            }

            if (!message.isSet(Flag.SEEN)) {
                for (MessagingListener messagingListener : getListeners(listener)) {
                    messagingListener.synchronizeMailboxNewMessage(account, folderServerId, message);
                }
            }
        }

        @Override
        public void syncRemovedMessage(@NotNull String folderServerId, @NotNull String messageServerId) {
            for (MessagingListener messagingListener : getListeners(listener)) {
                messagingListener.synchronizeMailboxRemovedMessage(account, folderServerId, messageServerId);
            }
        }

        @Override
        public void syncFlagChanged(@NotNull String folderServerId, @NotNull String messageServerId) {
            boolean shouldBeNotifiedOf = false;
            LocalMessage message = loadMessage(folderServerId, messageServerId);
            if (message.isSet(Flag.DELETED) || isMessageSuppressed(message)) {
                syncRemovedMessage(folderServerId, message.getUid());
            } else {
                LocalFolder localFolder = message.getFolder();
                if (notificationStrategy.shouldNotifyForMessage(account, localFolder, message, false)) {
                    shouldBeNotifiedOf = true;
                }
            }

            // we're only interested in messages that need removing
            if (!shouldBeNotifiedOf) {
                MessageReference messageReference = message.makeMessageReference();
                notificationController.removeNewMailNotification(account, messageReference);
            }
        }

        @Override
        public void syncFinished(@NotNull String folderServerId) {
            for (MessagingListener messagingListener : getListeners(listener)) {
                messagingListener.synchronizeMailboxFinished(account, folderServerId);
            }
        }

        @Override
        public void syncFailed(@NotNull String folderServerId, @NotNull String message, Exception exception) {
            syncFailed = true;

            if (exception instanceof AuthenticationFailedException) {
                handleAuthenticationFailure(account, true);
            } else {
                notifyUserIfCertificateProblem(account, exception, true);
            }

            for (MessagingListener messagingListener : getListeners(listener)) {
                messagingListener.synchronizeMailboxFailed(account, folderServerId, message);
            }
        }

        @Override
        public void folderStatusChanged(@NotNull String folderServerId) {
            for (MessagingListener messagingListener : getListeners(listener)) {
                messagingListener.folderStatusChanged(account, folderServerId);
            }
        }

        private LocalMessage loadMessage(String folderServerId, String messageServerId) {
            try {
                LocalFolder localFolder = localStore.getFolder(folderServerId);
                localFolder.open();
                return localFolder.getMessage(messageServerId);
            } catch (MessagingException e) {
                throw new RuntimeException("Couldn't load message (" + folderServerId + ":" + messageServerId + ")", e);
            }
        }
    }

    private enum MoveOrCopyFlavor {
        MOVE, COPY, MOVE_AND_MARK_AS_READ
    }
}
