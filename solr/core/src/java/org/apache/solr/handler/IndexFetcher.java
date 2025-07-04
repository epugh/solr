/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.handler;

import static org.apache.solr.common.params.CommonParams.JAVABIN;
import static org.apache.solr.common.params.CommonParams.NAME;
import static org.apache.solr.handler.ReplicationHandler.ALIAS;
import static org.apache.solr.handler.ReplicationHandler.CMD_DETAILS;
import static org.apache.solr.handler.ReplicationHandler.CMD_GET_FILE;
import static org.apache.solr.handler.ReplicationHandler.CMD_GET_FILE_LIST;
import static org.apache.solr.handler.ReplicationHandler.CMD_INDEX_VERSION;
import static org.apache.solr.handler.ReplicationHandler.COMMAND;
import static org.apache.solr.handler.ReplicationHandler.CONF_FILES;
import static org.apache.solr.handler.ReplicationHandler.FETCH_FROM_LEADER;
import static org.apache.solr.handler.ReplicationHandler.LEADER_URL;
import static org.apache.solr.handler.ReplicationHandler.SIZE;
import static org.apache.solr.handler.ReplicationHandler.SKIP_COMMIT_ON_LEADER_VERSION_ZERO;
import static org.apache.solr.handler.admin.api.ReplicationAPIBase.CHECKSUM;
import static org.apache.solr.handler.admin.api.ReplicationAPIBase.COMPRESSION;
import static org.apache.solr.handler.admin.api.ReplicationAPIBase.CONF_FILE_SHORT;
import static org.apache.solr.handler.admin.api.ReplicationAPIBase.FILE;
import static org.apache.solr.handler.admin.api.ReplicationAPIBase.FILE_STREAM;
import static org.apache.solr.handler.admin.api.ReplicationAPIBase.GENERATION;
import static org.apache.solr.handler.admin.api.ReplicationAPIBase.OFFSET;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.net.MalformedURLException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.Adler32;
import java.util.zip.Checksum;
import java.util.zip.InflaterInputStream;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.solr.client.api.model.FileMetaData;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.impl.HttpClientUtil;
import org.apache.solr.client.solrj.impl.InputStreamResponseParser;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.cloud.CloudDescriptor;
import org.apache.solr.cloud.ZkController;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.FastInputStream;
import org.apache.solr.common.util.IOUtils;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SolrNamedThreadFactory;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.util.SuppressForbidden;
import org.apache.solr.common.util.URLUtil;
import org.apache.solr.core.DirectoryFactory;
import org.apache.solr.core.DirectoryFactory.DirContext;
import org.apache.solr.core.IndexDeletionPolicyWrapper;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.admin.api.ReplicationAPIBase;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.security.AllowListUrlChecker;
import org.apache.solr.update.CommitUpdateCommand;
import org.apache.solr.update.UpdateShardHandler;
import org.apache.solr.util.IndexOutputOutputStream;
import org.apache.solr.util.RTimer;
import org.apache.solr.util.RefCounted;
import org.apache.solr.util.TestInjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides functionality of downloading changed index files as well as config files and a timer for
 * scheduling fetches from the leader.
 *
 * @since solr 1.4
 */
public class IndexFetcher {
  private static final int _100K = 100000;

  public static final String INDEX_PROPERTIES = "index.properties";

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private String leaderCoreUrl;
  // Derived from 'leaderCoreUrl' but kept around to avoid recomputing
  private String leaderBaseUrl;
  private String leaderCoreName;

  final ReplicationHandler replicationHandler;

  private volatile Date replicationStartTimeStamp;
  private RTimer replicationTimer;

  private final SolrCore solrCore;

  private volatile List<Map<String, Object>> filesToDownload;

  private volatile List<Map<String, Object>> confFilesToDownload;

  private volatile List<Map<String, Object>> filesDownloaded;

  private volatile List<Map<String, Object>> confFilesDownloaded;

  private volatile Map<String, Object> currentFile;

  private volatile DirectoryFileFetcher dirFileFetcher;

  private volatile LocalFsFileFetcher localFileFetcher;

  private volatile ExecutorService fsyncService;

  private volatile boolean stop = false;

  private boolean useInternalCompression = false;

  private boolean useExternalCompression = false;

  boolean fetchFromLeader = false;

  private final Http2SolrClient solrClient;

  private Integer soTimeout;

  private boolean skipCommitOnLeaderVersionZero = true;

  private boolean clearLocalIndexFirst = false;

  private static final String INTERRUPT_RESPONSE_MESSAGE =
      "Interrupted while waiting for modify lock";

  public static class IndexFetchResult {
    private final String message;
    private final boolean successful;
    private final Throwable exception;

    public static final String FAILED_BY_INTERRUPT_MESSAGE = "Fetching index failed by interrupt";
    public static final String FAILED_BY_EXCEPTION_MESSAGE = "Fetching index failed by exception";

    /** pre-defined results */
    public static final IndexFetchResult ALREADY_IN_SYNC =
        new IndexFetchResult("Local index commit is already in sync with peer", true, null);

    public static final IndexFetchResult INDEX_FETCH_FAILURE =
        new IndexFetchResult("Fetching latest index is failed", false, null);
    public static final IndexFetchResult INDEX_FETCH_SUCCESS =
        new IndexFetchResult("Fetching latest index is successful", true, null);
    public static final IndexFetchResult LOCK_OBTAIN_FAILED =
        new IndexFetchResult("Obtaining SnapPuller lock failed", false, null);
    public static final IndexFetchResult CONTAINER_IS_SHUTTING_DOWN =
        new IndexFetchResult(
            "I was asked to replicate but CoreContainer is shutting down", false, null);
    public static final IndexFetchResult LEADER_VERSION_ZERO =
        new IndexFetchResult("Index in peer is empty and never committed yet", true, null);
    public static final IndexFetchResult NO_INDEX_COMMIT_EXIST =
        new IndexFetchResult("No IndexCommit in local index", false, null);
    public static final IndexFetchResult PEER_INDEX_COMMIT_DELETED =
        new IndexFetchResult(
            "No files to download because IndexCommit in peer was deleted", false, null);
    public static final IndexFetchResult EXPECTING_NON_LEADER =
        new IndexFetchResult("Replicating from leader but I'm the shard leader", false, null);
    public static final IndexFetchResult LEADER_IS_NOT_ACTIVE =
        new IndexFetchResult("Replicating from leader but leader is not active", false, null);

    IndexFetchResult(String message, boolean successful, Throwable exception) {
      this.message = message;
      this.successful = successful;
      this.exception = exception;
    }

    /*
     * @return exception thrown if failed by exception or interrupt, otherwise null
     */
    public Throwable getException() {
      return this.exception;
    }

    /*
     * @return true if index fetch was successful, false otherwise
     */
    public boolean getSuccessful() {
      return this.successful;
    }

    public String getMessage() {
      return this.message;
    }
  }

  // It's crucial not to remove the authentication credentials as they are essential for User
  // managed replication.
  // GitHub PR #2276
  private Http2SolrClient createSolrClient(
      SolrCore core, String httpBasicAuthUser, String httpBasicAuthPassword, String leaderBaseUrl) {
    final UpdateShardHandler updateShardHandler = core.getCoreContainer().getUpdateShardHandler();
    return new Http2SolrClient.Builder(leaderBaseUrl)
        .withHttpClient(updateShardHandler.getRecoveryOnlyHttpClient())
        .withBasicAuthCredentials(httpBasicAuthUser, httpBasicAuthPassword)
        .withIdleTimeout(soTimeout, TimeUnit.MILLISECONDS)
        .build();
  }

  public IndexFetcher(
      final NamedList<?> initArgs, final ReplicationHandler handler, final SolrCore sc) {
    solrCore = sc;
    Object fetchFromLeader = initArgs.get(FETCH_FROM_LEADER);
    if (fetchFromLeader != null && fetchFromLeader instanceof Boolean) {
      this.fetchFromLeader = (boolean) fetchFromLeader;
    }
    Object skipCommitOnLeaderVersionZero = initArgs.get(SKIP_COMMIT_ON_LEADER_VERSION_ZERO);
    if (skipCommitOnLeaderVersionZero != null && skipCommitOnLeaderVersionZero instanceof Boolean) {
      this.skipCommitOnLeaderVersionZero = (boolean) skipCommitOnLeaderVersionZero;
    }
    String leaderUrl = (String) initArgs.get(LEADER_URL);
    if (leaderUrl == null && !this.fetchFromLeader)
      throw new SolrException(
          SolrException.ErrorCode.SERVER_ERROR, "'leaderUrl' is required for a follower");
    if (leaderUrl != null && leaderUrl.endsWith(ReplicationHandler.PATH)) {
      leaderUrl = leaderUrl.substring(0, leaderUrl.length() - 12);
      log.warn("'leaderUrl' must be specified without the {} suffix", ReplicationHandler.PATH);
    }
    setLeaderCoreUrl(leaderUrl);

    this.replicationHandler = handler;
    String compress = (String) initArgs.get(COMPRESSION);
    useInternalCompression = ReplicationHandler.INTERNAL.equals(compress);
    useExternalCompression = ReplicationHandler.EXTERNAL.equals(compress);
    soTimeout = getParameter(initArgs, HttpClientUtil.PROP_SO_TIMEOUT, 120000, null);

    String httpBasicAuthUser = (String) initArgs.get(HttpClientUtil.PROP_BASIC_AUTH_USER);
    String httpBasicAuthPassword = (String) initArgs.get(HttpClientUtil.PROP_BASIC_AUTH_PASS);
    solrClient =
        createSolrClient(solrCore, httpBasicAuthUser, httpBasicAuthPassword, leaderBaseUrl);
  }

  private void setLeaderCoreUrl(String leaderCoreUrl) {
    if (leaderCoreUrl != null) {
      leaderCoreUrl = leaderCoreUrl.trim();
      ClusterState clusterState =
          solrCore.getCoreContainer().getZkController() == null
              ? null
              : solrCore.getCoreContainer().getZkController().getClusterState();
      try {
        solrCore
            .getCoreContainer()
            .getAllowListUrlChecker()
            .checkAllowList(Collections.singletonList(leaderCoreUrl), clusterState);
      } catch (MalformedURLException e) {
        throw new SolrException(
            SolrException.ErrorCode.SERVER_ERROR, "Malformed 'leaderUrl' " + leaderCoreUrl, e);
      } catch (SolrException e) {
        throw new SolrException(
            SolrException.ErrorCode.FORBIDDEN,
            "The '"
                + LEADER_URL
                + "' parameter value '"
                + leaderCoreUrl
                + "' is not allowed: "
                + e.getMessage()
                + ". "
                + AllowListUrlChecker.SET_SOLR_DISABLE_URL_ALLOW_LIST_CLUE);
      }
    }
    this.leaderCoreUrl = leaderCoreUrl;
    if (leaderCoreUrl != null) {
      this.leaderBaseUrl = URLUtil.extractBaseUrl(leaderCoreUrl);
      this.leaderCoreName = URLUtil.extractCoreFromCoreUrl(leaderCoreUrl);
    }
  }

  protected <T> T getParameter(
      NamedList<?> initArgs, String configKey, T defaultValue, StringBuilder sb) {
    T toReturn = defaultValue;
    if (initArgs != null) {
      @SuppressWarnings("unchecked")
      T temp = (T) initArgs.get(configKey);
      toReturn = (temp != null) ? temp : defaultValue;
    }
    if (sb != null && toReturn != null)
      sb.append(configKey).append(" : ").append(toReturn).append(",");
    return toReturn;
  }

  /** Gets the latest commit version and generation from the leader */
  public NamedList<Object> getLatestVersion() throws IOException {
    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set(COMMAND, CMD_INDEX_VERSION);
    params.set(CommonParams.WT, JAVABIN);
    params.set(CommonParams.QT, ReplicationHandler.PATH);
    QueryRequest req = new QueryRequest(params);
    try {
      return solrClient.requestWithBaseUrl(leaderBaseUrl, leaderCoreName, req).getResponse();
    } catch (SolrServerException e) {
      throw new SolrException(ErrorCode.SERVER_ERROR, e.getMessage(), e);
    }
  }

  /**
   * Fetches the list of files in a given index commit point and updates internal list of files to
   * download.
   */
  @SuppressWarnings({"unchecked"})
  private void fetchFileList(long gen) throws IOException {
    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set(COMMAND, CMD_GET_FILE_LIST);
    params.set(GENERATION, String.valueOf(gen));
    params.set(CommonParams.WT, JAVABIN);
    params.set(CommonParams.QT, ReplicationHandler.PATH);
    QueryRequest req = new QueryRequest(params);
    try {
      NamedList<?> response =
          solrClient.requestWithBaseUrl(leaderBaseUrl, leaderCoreName, req).getResponse();

      List<Map<String, Object>> files = (List<Map<String, Object>>) response.get(CMD_GET_FILE_LIST);
      if (files != null) filesToDownload = Collections.synchronizedList(files);
      else {
        filesToDownload = Collections.emptyList();
        log.error("No files to download for index generation: {}", gen);
      }

      files = (List<Map<String, Object>>) response.get(CONF_FILES);
      if (files != null) confFilesToDownload = Collections.synchronizedList(files);
    } catch (SolrServerException e) {
      throw new IOException(e);
    }
  }

  IndexFetchResult fetchLatestIndex(boolean forceReplication)
      throws IOException, InterruptedException {
    return fetchLatestIndex(forceReplication, false);
  }

  /**
   * This command downloads all the necessary files from leader to install an index commit point.
   * Only changed files are downloaded. It also downloads the conf files (if they are modified).
   *
   * @param forceReplication force a replication in all cases
   * @param forceCoreReload force a core reload in all cases
   * @return true on success, false if follower is already in sync
   * @throws IOException if an exception occurs
   */
  IndexFetchResult fetchLatestIndex(boolean forceReplication, boolean forceCoreReload)
      throws IOException, InterruptedException {

    this.clearLocalIndexFirst = false;
    boolean cleanupDone = false;
    boolean successfulInstall = false;
    markReplicationStart();
    Directory tmpIndexDir = null;
    String tmpIndexDirPath;
    Directory indexDir = null;
    String indexDirPath;
    boolean deleteTmpIdxDir = true;
    Path tmpTlogDir = null;

    if (!solrCore.getSolrCoreState().getLastReplicateIndexSuccess()) {
      // if the last replication was not a success, we force a full replication
      // when we are a bit more confident we may want to try a partial replication
      // if the error is connection related or something, but we have to be careful
      forceReplication = true;
      log.info("Last replication failed, so I'll force replication");
    }

    try {
      if (fetchFromLeader) {
        assert !solrCore.isClosed() : "Replication should be stopped before closing the core";
        Replica replica = getLeaderReplica();
        CloudDescriptor cd = solrCore.getCoreDescriptor().getCloudDescriptor();
        if (cd.getCoreNodeName().equals(replica.getName())) {
          return IndexFetchResult.EXPECTING_NON_LEADER;
        }
        if (replica.getState() != Replica.State.ACTIVE) {
          if (log.isInfoEnabled()) {
            log.info(
                "Replica {} is leader but it's state is {}, skipping replication",
                replica.getName(),
                replica.getState());
          }
          return IndexFetchResult.LEADER_IS_NOT_ACTIVE;
        }
        if (!solrCore
            .getCoreContainer()
            .getZkController()
            .getClusterState()
            .liveNodesContain(replica.getNodeName())) {
          if (log.isInfoEnabled()) {
            log.info(
                "Replica {} is leader but it's not hosted on a live node, skipping replication",
                replica.getName());
          }
          return IndexFetchResult.LEADER_IS_NOT_ACTIVE;
        }
        if (!replica.getCoreUrl().equals(leaderCoreUrl)) {
          setLeaderCoreUrl(replica.getCoreUrl());
          log.info("Updated leaderUrl to {}", leaderCoreUrl);
          // TODO: Do we need to set forceReplication = true?
        } else {
          log.debug("leaderUrl didn't change");
        }
      }
      // get the current 'replicateable' index version in the leader
      NamedList<?> response;
      try {
        response = getLatestVersion();
      } catch (Exception e) {
        final String errorMsg = e.toString();
        if (StrUtils.isNotNullOrEmpty(errorMsg) && errorMsg.contains(INTERRUPT_RESPONSE_MESSAGE)) {
          log.warn(
              "Leader at: {} is not available. Index fetch failed by interrupt. Exception: {}",
              leaderCoreUrl,
              errorMsg);
          return new IndexFetchResult(IndexFetchResult.FAILED_BY_INTERRUPT_MESSAGE, false, e);
        } else {
          log.warn(
              "Leader at: {} is not available. Index fetch failed by exception: {}",
              leaderCoreUrl,
              errorMsg);
          return new IndexFetchResult(IndexFetchResult.FAILED_BY_EXCEPTION_MESSAGE, false, e);
        }
      }

      long latestVersion = (Long) response.get(CMD_INDEX_VERSION);
      long latestGeneration = (Long) response.get(GENERATION);

      log.info("Leader's generation: {}", latestGeneration);
      log.info("Leader's version: {}", latestVersion);

      // TODO: make sure that getLatestCommit only returns commit points for the main index (i.e. no
      // side-car indexes)
      IndexCommit commit = solrCore.getDeletionPolicy().getLatestCommit();
      if (commit == null) {
        // Presumably the IndexWriter hasn't been opened yet, and hence the deletion policy hasn't
        // been updated with commit points
        RefCounted<SolrIndexSearcher> searcherRefCounted = null;
        try {
          searcherRefCounted = solrCore.getNewestSearcher(false);
          if (searcherRefCounted == null) {
            log.warn("No open searcher found - fetch aborted");
            return IndexFetchResult.NO_INDEX_COMMIT_EXIST;
          }
          commit = searcherRefCounted.get().getIndexReader().getIndexCommit();
        } finally {
          if (searcherRefCounted != null) searcherRefCounted.decref();
        }
      }

      if (log.isInfoEnabled()) {
        log.info("Follower's generation: {}", commit.getGeneration());
        log.info(
            "Follower's version: {}",
            IndexDeletionPolicyWrapper.getCommitTimestamp(commit)); // nowarn
      }

      // Leader's version is 0 and generation is 0 -  not open for replication
      if (latestVersion == 0L && latestGeneration == 0L) {
        log.info("Leader's version is 0 and generation is 0 -  not open for replication");
        return IndexFetchResult.LEADER_IS_NOT_ACTIVE;
      }

      if (latestVersion == 0L) {
        if (IndexDeletionPolicyWrapper.getCommitTimestamp(commit) != 0L) {
          // since we won't get the files for an empty index,
          // we just clear ours and commit
          log.info("New index in Leader. Deleting mine...");
          RefCounted<IndexWriter> iw =
              solrCore.getUpdateHandler().getSolrCoreState().getIndexWriter(solrCore);
          try {
            iw.get().deleteAll();
          } finally {
            iw.decref();
          }
          assert TestInjection.injectDelayBeforeFollowerCommitRefresh();
          if (skipCommitOnLeaderVersionZero) {
            openNewSearcherAndUpdateCommitPoint();
          } else {
            SolrQueryRequest req = new LocalSolrQueryRequest(solrCore, new ModifiableSolrParams());
            solrCore.getUpdateHandler().commit(new CommitUpdateCommand(req, false));
          }
        }

        // there is nothing to be replicated
        successfulInstall = true;
        log.debug("Nothing to replicate, leader's version is 0");
        return IndexFetchResult.LEADER_VERSION_ZERO;
      }

      // TODO: Should we be comparing timestamps (across machines) here?
      if (!forceReplication
          && IndexDeletionPolicyWrapper.getCommitTimestamp(commit) == latestVersion) {
        // leader and follower are already in sync just return
        log.info("Follower in sync with leader.");
        successfulInstall = true;
        return IndexFetchResult.ALREADY_IN_SYNC;
      }
      log.info("Starting replication process");
      // get the list of files first
      fetchFileList(latestGeneration);
      // this can happen if the commit point is deleted before we fetch the file list.
      if (filesToDownload.isEmpty()) {
        return IndexFetchResult.PEER_INDEX_COMMIT_DELETED;
      }
      if (log.isInfoEnabled()) {
        log.info("Number of files in latest index in leader: {}", filesToDownload.size());
      }

      // Create the sync service
      fsyncService =
          ExecutorUtil.newMDCAwareSingleThreadExecutor(new SolrNamedThreadFactory("fsyncService"));
      // use a synchronized list because the list is read by other threads (to show details)
      filesDownloaded = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
      // if the generation of leader is older than that of the follower, it means they are not
      // compatible to be copied then a new index directory to be created and all the files need to
      // be copied
      boolean isFullCopyNeeded =
          IndexDeletionPolicyWrapper.getCommitTimestamp(commit) >= latestVersion
              || commit.getGeneration() >= latestGeneration
              || forceReplication;

      String timestamp = new SimpleDateFormat(SnapShooter.DATE_FMT, Locale.ROOT).format(new Date());
      String tmpIdxDirName = "index." + timestamp;
      tmpIndexDirPath = solrCore.getDataDir() + tmpIdxDirName;

      tmpIndexDir =
          solrCore
              .getDirectoryFactory()
              .get(
                  tmpIndexDirPath,
                  DirContext.DEFAULT,
                  solrCore.getSolrConfig().indexConfig.lockType);

      // cindex dir...
      indexDirPath = solrCore.getIndexDir();
      indexDir =
          solrCore
              .getDirectoryFactory()
              .get(indexDirPath, DirContext.DEFAULT, solrCore.getSolrConfig().indexConfig.lockType);

      try {

        // We will compare all the index files from the leader vs the index files on disk to see if
        // there is a mismatch in the metadata. If there is a mismatch for the same index file then
        // we download the entire index (except when differential copy is applicable) again.
        if (!isFullCopyNeeded && isIndexStale(indexDir)) {
          isFullCopyNeeded = true;
        }

        if (!isFullCopyNeeded && !fetchFromLeader) {
          // a searcher might be using some flushed but not committed segments
          // because of soft commits (which open a searcher on IW's data)
          // so we need to close the existing searcher on the last commit
          // and wait until we are able to clean up all unused lucene files
          if (solrCore.getCoreContainer().isZooKeeperAware()) {
            solrCore.closeSearcher();
          }

          // rollback and reopen index writer and wait until all unused files
          // are successfully deleted
          solrCore.getUpdateHandler().newIndexWriter(true);
          RefCounted<IndexWriter> writer =
              solrCore.getUpdateHandler().getSolrCoreState().getIndexWriter(null);
          try {
            IndexWriter indexWriter = writer.get();
            int c = 0;
            indexWriter.deleteUnusedFiles();
            while (hasUnusedFiles(indexDir, commit)) {
              indexWriter.deleteUnusedFiles();
              log.info(
                  "Sleeping for 1000ms to wait for unused lucene index files to be delete-able");
              Thread.sleep(1000);
              c++;
              if (c >= 30) {
                log.warn(
                    "IndexFetcher unable to cleanup unused lucene index files so we must do a full copy instead");
                isFullCopyNeeded = true;
                break;
              }
            }
            if (c > 0) {
              log.info(
                  "IndexFetcher slept for {}ms for unused lucene index files to be delete-able",
                  c * 1000);
            }
          } finally {
            writer.decref();
          }
        }
        boolean reloadCore = false;

        try {
          // we have to be careful and do this after we know isFullCopyNeeded won't be flipped
          if (!isFullCopyNeeded) {
            solrCore.getUpdateHandler().getSolrCoreState().closeIndexWriter(solrCore, true);
          }

          log.info("Starting download (fullCopy={}) to {}", isFullCopyNeeded, tmpIndexDir);
          successfulInstall = false;

          long bytesDownloaded =
              downloadIndexFiles(
                  isFullCopyNeeded,
                  indexDir,
                  tmpIndexDir,
                  indexDirPath,
                  tmpIndexDirPath,
                  latestGeneration);
          final long timeTakenSeconds = getReplicationTimeElapsed();
          final Long bytesDownloadedPerSecond =
              (timeTakenSeconds != 0 ? bytesDownloaded / timeTakenSeconds : null);
          log.info(
              "Total time taken for download (fullCopy={},bytesDownloaded={}) : {} secs ({} bytes/sec) to {}",
              isFullCopyNeeded,
              bytesDownloaded,
              timeTakenSeconds,
              bytesDownloadedPerSecond,
              tmpIndexDir);

          Collection<Map<String, Object>> modifiedConfFiles =
              getModifiedConfFiles(confFilesToDownload);
          if (!modifiedConfFiles.isEmpty()) {
            reloadCore = true;
            downloadConfFiles(confFilesToDownload, latestGeneration);
            if (isFullCopyNeeded) {
              successfulInstall = solrCore.modifyIndexProps(tmpIdxDirName);
              if (successfulInstall) deleteTmpIdxDir = false;
            } else {
              successfulInstall = moveIndexFiles(tmpIndexDir, indexDir);
            }
            if (successfulInstall) {
              if (isFullCopyNeeded) {
                // let the system know we are changing dir's and the old one
                // may be closed
                if (indexDir != null) {
                  if (!this.clearLocalIndexFirst) { // it was closed earlier
                    solrCore.getDirectoryFactory().doneWithDirectory(indexDir);
                  }
                  // Cleanup all index files not associated with any *named* snapshot.
                  solrCore.deleteNonSnapshotIndexFiles(indexDirPath);
                }
              }

              log.info("Configuration files are modified, core will be reloaded");
              // write to a file time of replication and conf files.
              logReplicationTimeAndConfFiles(modifiedConfFiles, successfulInstall);
            }
          } else {
            terminateAndWaitFsyncService();
            if (isFullCopyNeeded) {
              successfulInstall = solrCore.modifyIndexProps(tmpIdxDirName);
              if (successfulInstall) deleteTmpIdxDir = false;
            } else {
              successfulInstall = moveIndexFiles(tmpIndexDir, indexDir);
            }
            if (successfulInstall) {
              logReplicationTimeAndConfFiles(modifiedConfFiles, successfulInstall);
            }
          }
        } finally {
          solrCore.searchEnabled = true;
          solrCore.indexEnabled = true;
          if (!isFullCopyNeeded) {
            solrCore.getUpdateHandler().getSolrCoreState().openIndexWriter(solrCore);
          }
        }

        // we must reload the core after we open the IW back up
        if (successfulInstall && (reloadCore || forceCoreReload)) {
          if (log.isInfoEnabled()) {
            log.info("Reloading SolrCore {}", solrCore.getName());
          }
          reloadCore();
        }

        if (successfulInstall) {
          if (isFullCopyNeeded) {
            // let the system know we are changing dir's and the old one
            // may be closed
            if (indexDir != null) {
              log.info("removing old index directory {}", indexDir);
              solrCore.getDirectoryFactory().doneWithDirectory(indexDir);
              solrCore.getDirectoryFactory().remove(indexDir);
            }
          }
          if (isFullCopyNeeded) {
            solrCore.getUpdateHandler().newIndexWriter(isFullCopyNeeded);
          }

          openNewSearcherAndUpdateCommitPoint();
        }

        if (!isFullCopyNeeded && !forceReplication && !successfulInstall) {
          cleanup(solrCore, tmpIndexDir, indexDir, deleteTmpIdxDir, tmpTlogDir, successfulInstall);
          cleanupDone = true;
          // we try with a full copy of the index
          log.warn(
              "Replication attempt was not successful - trying a full index replication reloadCore={}",
              reloadCore);
          successfulInstall = fetchLatestIndex(true, reloadCore).getSuccessful();
        }

        markReplicationStop();
        return successfulInstall
            ? IndexFetchResult.INDEX_FETCH_SUCCESS
            : IndexFetchResult.INDEX_FETCH_FAILURE;
      } catch (ReplicationHandlerException e) {
        log.error("User aborted Replication", e);
        return new IndexFetchResult(IndexFetchResult.FAILED_BY_EXCEPTION_MESSAGE, false, e);
      } catch (SolrException e) {
        throw e;
      } catch (InterruptedException e) {
        throw (InterruptedException)
            (new InterruptedException("Index fetch interrupted").initCause(e));
      } catch (Exception e) {
        throw new SolrException(ErrorCode.SERVER_ERROR, "Index fetch failed : ", e);
      }
    } finally {
      if (!cleanupDone) {
        cleanup(solrCore, tmpIndexDir, indexDir, deleteTmpIdxDir, tmpTlogDir, successfulInstall);
      }
    }
  }

  private Replica getLeaderReplica() throws InterruptedException {
    ZkController zkController = solrCore.getCoreContainer().getZkController();
    CloudDescriptor cd = solrCore.getCoreDescriptor().getCloudDescriptor();
    Replica leaderReplica =
        zkController.getZkStateReader().getLeaderRetry(cd.getCollectionName(), cd.getShardId());
    return leaderReplica;
  }

  private void cleanup(
      final SolrCore core,
      Directory tmpIndexDir,
      Directory indexDir,
      boolean deleteTmpIdxDir,
      Path tmpTlogDir,
      boolean successfulInstall)
      throws IOException {
    try {
      if (!successfulInstall) {
        try {
          logReplicationTimeAndConfFiles(null, successfulInstall);
        } catch (Exception e) {
          // this can happen on shutdown, a fetch may be running in a thread after DirectoryFactory
          // is closed
          log.warn("Could not log failed replication details", e);
        }
      }

      if (core.getCoreContainer().isZooKeeperAware()) {
        // we only track replication success in SolrCloud mode
        core.getUpdateHandler().getSolrCoreState().setLastReplicateIndexSuccess(successfulInstall);
      }

      filesToDownload = filesDownloaded = confFilesDownloaded = confFilesToDownload = null;
      markReplicationStop();
      dirFileFetcher = null;
      localFileFetcher = null;
      if (fsyncService != null && !ExecutorUtil.isShutdown(fsyncService)) fsyncService.shutdown();
      fsyncService = null;
      stop = false;
      fsyncException = null;
    } finally {
      // order below is important
      try {
        if (tmpIndexDir != null && deleteTmpIdxDir) {
          core.getDirectoryFactory().doneWithDirectory(tmpIndexDir);
          core.getDirectoryFactory().remove(tmpIndexDir);
        }
      } catch (Exception e) {
        log.error("Error cleaning up tmpIndexDir", e);
      } finally {
        try {
          if (tmpIndexDir != null) core.getDirectoryFactory().release(tmpIndexDir);
        } catch (Exception e) {
          log.error("Error releasing tmpIndexDir", e);
        }
        try {
          if (indexDir != null) {
            core.getDirectoryFactory().release(indexDir);
          }
        } catch (Exception e) {
          log.error("Error releasing indexDir", e);
        }
        try {
          if (tmpTlogDir != null) {
            delTree(tmpTlogDir);
          }
        } catch (Exception e) {
          log.error("Error deleting tmpTlogDir", e);
        }
      }
    }
  }

  private boolean hasUnusedFiles(Directory indexDir, IndexCommit commit) throws IOException {
    String segmentsFileName = commit.getSegmentsFileName();
    SegmentInfos infos = SegmentInfos.readCommit(indexDir, segmentsFileName);
    Set<String> currentFiles = new HashSet<>(infos.files(true));
    String[] allFiles = indexDir.listAll();
    for (String file : allFiles) {
      if (!file.equals(segmentsFileName)
          && !currentFiles.contains(file)
          && !file.endsWith(".lock")) {
        log.info("Found unused file: {}", file);
        return true;
      }
    }
    return false;
  }

  private volatile Exception fsyncException;

  /**
   * terminate the fsync service and wait for all the tasks to complete. If it is already terminated
   */
  private void terminateAndWaitFsyncService() throws Exception {
    if (ExecutorUtil.isTerminated(fsyncService)) return;
    fsyncService.shutdown();
    // give a long wait say 1 hr
    fsyncService.awaitTermination(3600, TimeUnit.SECONDS);
    // if any fsync failed, throw that exception back
    Exception fsyncExceptionCopy = fsyncException;
    if (fsyncExceptionCopy != null) throw fsyncExceptionCopy;
  }

  /**
   * Helper method to record the last replication's details so that we can show them on the
   * statistics page across restarts.
   *
   * @throws IOException on IO error
   */
  @SuppressForbidden(reason = "Need currentTimeMillis for debugging/stats")
  private void logReplicationTimeAndConfFiles(
      Collection<Map<String, Object>> modifiedConfFiles, boolean successfulInstall)
      throws IOException {
    List<String> confFiles = new ArrayList<>();
    if (modifiedConfFiles != null && !modifiedConfFiles.isEmpty())
      for (Map<String, Object> map1 : modifiedConfFiles) confFiles.add((String) map1.get(NAME));

    Properties props = replicationHandler.loadReplicationProperties();
    long replicationTime = System.currentTimeMillis();
    long replicationTimeTaken = getReplicationTimeElapsed();
    Directory dir = null;
    try {
      dir =
          solrCore
              .getDirectoryFactory()
              .get(
                  solrCore.getDataDir(),
                  DirContext.META_DATA,
                  solrCore.getSolrConfig().indexConfig.lockType);

      int indexCount = 1, confFilesCount = 1;
      if (props.containsKey(TIMES_INDEX_REPLICATED)) {
        indexCount = Integer.parseInt(props.getProperty(TIMES_INDEX_REPLICATED)) + 1;
      }
      StringBuilder sb =
          readToStringBuilder(replicationTime, props.getProperty(INDEX_REPLICATED_AT_LIST));
      props.setProperty(INDEX_REPLICATED_AT_LIST, sb.toString());
      props.setProperty(INDEX_REPLICATED_AT, String.valueOf(replicationTime));
      props.setProperty(PREVIOUS_CYCLE_TIME_TAKEN, String.valueOf(replicationTimeTaken));
      props.setProperty(TIMES_INDEX_REPLICATED, String.valueOf(indexCount));
      if (clearLocalIndexFirst) {
        props.setProperty(CLEARED_LOCAL_IDX, "true");
      }
      if (modifiedConfFiles != null && !modifiedConfFiles.isEmpty()) {
        props.setProperty(CONF_FILES_REPLICATED, confFiles.toString());
        props.setProperty(CONF_FILES_REPLICATED_AT, String.valueOf(replicationTime));
        if (props.containsKey(TIMES_CONFIG_REPLICATED)) {
          confFilesCount = Integer.parseInt(props.getProperty(TIMES_CONFIG_REPLICATED)) + 1;
        }
        props.setProperty(TIMES_CONFIG_REPLICATED, String.valueOf(confFilesCount));
      }

      props.setProperty(LAST_CYCLE_BYTES_DOWNLOADED, String.valueOf(getTotalBytesDownloaded()));
      if (!successfulInstall) {
        int numFailures = 1;
        if (props.containsKey(TIMES_FAILED)) {
          numFailures = Integer.parseInt(props.getProperty(TIMES_FAILED)) + 1;
        }
        props.setProperty(TIMES_FAILED, String.valueOf(numFailures));
        props.setProperty(REPLICATION_FAILED_AT, String.valueOf(replicationTime));
        sb = readToStringBuilder(replicationTime, props.getProperty(REPLICATION_FAILED_AT_LIST));
        props.setProperty(REPLICATION_FAILED_AT_LIST, sb.toString());
      }

      String tmpFileName = REPLICATION_PROPERTIES + "." + System.nanoTime();
      final IndexOutput out = dir.createOutput(tmpFileName, DirectoryFactory.IOCONTEXT_NO_CACHE);
      try (Writer outFile =
          new OutputStreamWriter(new IndexOutputOutputStream(out), StandardCharsets.UTF_8)) {
        props.store(outFile, "Replication details");
        dir.sync(Collections.singleton(tmpFileName));
      }

      solrCore.getDirectoryFactory().renameWithOverwrite(dir, tmpFileName, REPLICATION_PROPERTIES);
    } catch (Exception e) {
      log.warn("Exception while updating statistics", e);
    } finally {
      if (dir != null) {
        solrCore.getDirectoryFactory().release(dir);
      }
    }
  }

  long getTotalBytesDownloaded() {
    long bytesDownloaded = 0;
    // get size from list of files to download
    for (Map<String, Object> file : getFilesDownloaded()) {
      bytesDownloaded += (Long) file.get(SIZE);
    }

    // get size from list of conf files to download
    for (Map<String, Object> file : getConfFilesDownloaded()) {
      bytesDownloaded += (Long) file.get(SIZE);
    }

    // get size from current file being downloaded
    Map<String, Object> currentFile = getCurrentFile();
    if (currentFile != null) {
      if (currentFile.containsKey("bytesDownloaded")) {
        bytesDownloaded += (Long) currentFile.get("bytesDownloaded");
      }
    }
    return bytesDownloaded;
  }

  private StringBuilder readToStringBuilder(long replicationTime, String str) {
    StringBuilder sb = new StringBuilder();
    List<String> l = new ArrayList<>();
    if (str != null && str.length() != 0) {
      String[] ss = str.split(",");
      Collections.addAll(l, ss);
    }
    sb.append(replicationTime);
    if (!l.isEmpty()) {
      for (int i = 0; i < l.size() || i < 9; i++) {
        if (i == l.size() || i == 9) break;
        String s = l.get(i);
        sb.append(",").append(s);
      }
    }
    return sb;
  }

  private void openNewSearcherAndUpdateCommitPoint() throws IOException {
    IndexCommit commitPoint;
    // must get the latest solrCore object because the one we have might be closed because of a
    // reload
    // todo stop keeping solrCore around
    try (SolrCore core = solrCore.getCoreContainer().getCore(solrCore.getName())) {
      if (core == null) {
        return; // core closed, presumably
      }
      @SuppressWarnings("unchecked")
      Future<Void>[] waitSearcher = (Future<Void>[]) Array.newInstance(Future.class, 1);
      RefCounted<SolrIndexSearcher> searcher = core.getSearcher(true, true, waitSearcher, true);
      try {
        if (waitSearcher[0] != null) {
          try {
            waitSearcher[0].get();
          } catch (InterruptedException | ExecutionException e) {
            log.error("Exception waiting for searcher", e);
          }
        }
        commitPoint = searcher.get().getIndexReader().getIndexCommit();
      } finally {
        if (searcher != null) {
          searcher.decref();
        }
      }
    }

    // update the commit point in replication handler
    replicationHandler.indexCommitPoint = commitPoint;
  }

  private void reloadCore() {
    final CountDownLatch latch = new CountDownLatch(1);
    new Thread(
            () -> {
              try {
                solrCore.getCoreContainer().reload(solrCore.getName(), solrCore.uniqueId);
              } catch (Exception e) {
                log.error("Could not reload core ", e);
              } finally {
                latch.countDown();
              }
            },
            "CoreReload")
        .start();
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while waiting for core reload to finish", e);
    }
  }

  private void downloadConfFiles(
      List<Map<String, Object>> confFilesToDownload, long latestGeneration) {
    log.info("Starting download of configuration files from leader: {}", confFilesToDownload);
    confFilesDownloaded = Collections.synchronizedList(new ArrayList<>());
    Path tmpConfDir =
        solrCore.getResourceLoader().getConfigPath().resolve("conf." + getDateAsStr(new Date()));
    try {
      try {
        Files.createDirectories(tmpConfDir);
      } catch (Exception e) {
        throw new SolrException(
            SolrException.ErrorCode.SERVER_ERROR,
            "Failed to create temporary config folder: " + tmpConfDir.getFileName());
      }
      for (Map<String, Object> file : confFilesToDownload) {
        String saveAs = (String) (file.get(ALIAS) == null ? file.get(NAME) : file.get(ALIAS));
        localFileFetcher =
            new LocalFsFileFetcher(tmpConfDir, file, saveAs, CONF_FILE_SHORT, latestGeneration);
        currentFile = file;
        localFileFetcher.fetchFile();
        confFilesDownloaded.add(new HashMap<>(file));
      }
      // this is called before copying the files to the original conf dir
      // so that if there is an exception avoid corrupting the original files.
      terminateAndWaitFsyncService();
      copyTmpConfFiles2Conf(tmpConfDir);

    } catch (Exception e) {
      throw new SolrException(
          ErrorCode.SERVER_ERROR, "Failed to download configuration files from leader", e);
    } finally {
      delTree(tmpConfDir);
    }
  }

  /**
   * Download the index files. If a new index is needed, download all the files.
   *
   * @param downloadCompleteIndex is it a fresh index copy
   * @param indexDir the indexDir to be merged to
   * @param tmpIndexDir the directory to which files need to be downloaded to
   * @param indexDirPath the path of indexDir
   * @param latestGeneration the version number
   * @return number of bytes downloaded
   */
  private long downloadIndexFiles(
      boolean downloadCompleteIndex,
      Directory indexDir,
      Directory tmpIndexDir,
      String indexDirPath,
      String tmpIndexDirPath,
      long latestGeneration)
      throws Exception {
    if (log.isDebugEnabled()) {
      log.debug("Download files to dir: {}", Arrays.asList(indexDir.listAll()));
    }
    long bytesDownloaded = 0;
    long bytesSkippedCopying = 0;
    boolean doDifferentialCopy =
        (indexDir instanceof FSDirectory
                || (indexDir instanceof FilterDirectory
                    && FilterDirectory.unwrap(indexDir) instanceof FSDirectory))
            && (tmpIndexDir instanceof FSDirectory
                || (tmpIndexDir instanceof FilterDirectory
                    && FilterDirectory.unwrap(tmpIndexDir) instanceof FSDirectory));

    long totalSpaceRequired = 0;
    for (Map<String, Object> file : filesToDownload) {
      long size = (Long) file.get(SIZE);
      totalSpaceRequired += size;
    }

    if (log.isInfoEnabled()) {
      log.info(
          "tmpIndexDir_type  : {} , {}",
          tmpIndexDir.getClass(),
          FilterDirectory.unwrap(tmpIndexDir));
    }
    long usableSpace = usableDiskSpaceProvider.apply(tmpIndexDirPath);
    if (getApproxTotalSpaceReqd(totalSpaceRequired) > usableSpace) {
      deleteFilesInAdvance(indexDir, indexDirPath, totalSpaceRequired, usableSpace);
    }

    for (Map<String, Object> file : filesToDownload) {
      String filename = (String) file.get(NAME);
      long size = (Long) file.get(SIZE);
      CompareResult compareResult =
          compareFile(indexDir, filename, size, (Long) file.get(CHECKSUM));
      boolean alwaysDownload = filesToAlwaysDownloadIfNoChecksums(filename, size, compareResult);
      if (log.isDebugEnabled()) {
        log.debug(
            "Downloading file={} size={} checksum={} alwaysDownload={}",
            filename,
            size,
            file.get(CHECKSUM),
            alwaysDownload);
      }
      if (!compareResult.equal || downloadCompleteIndex || alwaysDownload) {
        Path localFile = Path.of(indexDirPath, filename);
        if (downloadCompleteIndex
            && doDifferentialCopy
            && compareResult.equal
            && compareResult.checkSummed
            && Files.exists(localFile)) {
          if (log.isInfoEnabled()) {
            log.info(
                "Don't need to download this file. Local file's path is: {}, checksum is: {}",
                localFile.toAbsolutePath(),
                file.get(CHECKSUM));
          }
          // A hard link here should survive the eventual directory move, and should be more space
          // efficient as compared to a file copy. TODO: Maybe we could do a move safely here?
          Files.createLink(Path.of(tmpIndexDirPath, filename), localFile);
          bytesSkippedCopying += Files.size(localFile);
        } else {
          dirFileFetcher =
              new DirectoryFileFetcher(
                  tmpIndexDir, file, (String) file.get(NAME), FILE, latestGeneration);
          currentFile = file;
          dirFileFetcher.fetchFile();
          bytesDownloaded += dirFileFetcher.getBytesDownloaded();
        }
        filesDownloaded.add(new HashMap<>(file));
      } else {
        if (log.isDebugEnabled()) {
          log.debug("Skipping download for {} because it already exists", file.get(NAME));
        }
      }
    }
    log.info(
        "Bytes downloaded: {}, Bytes skipped downloading: {}",
        bytesDownloaded,
        bytesSkippedCopying);
    return bytesDownloaded;
  }

  // only for testing purposes. do not use this anywhere else
  // -----------START----------------------
  static BooleanSupplier testWait = () -> true;
  static Function<String, Long> usableDiskSpaceProvider = dir -> getUsableSpace(dir);

  // ------------ END---------------------

  private static Long getUsableSpace(String dir) {
    try {
      Path file = Path.of(dir);
      if (Files.notExists(file)) {
        file = file.getParent();
        // this is not a disk directory. so just pretend that there is enough space
        if (Files.notExists(file)) {
          return Long.MAX_VALUE;
        }
      }
      FileStore fileStore = Files.getFileStore(file);
      return fileStore.getUsableSpace();
    } catch (IOException e) {
      throw new SolrException(ErrorCode.SERVER_ERROR, "Could not free disk space", e);
    }
  }

  private long getApproxTotalSpaceReqd(long totalSpaceRequired) {
    long approxTotalSpaceReqd = (long) (totalSpaceRequired * 1.05); // add 5% extra for safety
    // we should have an extra of 100MB free after everything is downloaded
    approxTotalSpaceReqd += (100 * 1024 * 1024);
    return approxTotalSpaceReqd;
  }

  private void deleteFilesInAdvance(
      Directory indexDir, String indexDirPath, long usableDiskSpace, long totalSpaceRequired)
      throws IOException {
    long actualSpaceReqd = totalSpaceRequired;
    List<String> filesTobeDeleted = new ArrayList<>();
    long clearedSpace = 0;
    // go through each file to check if this needs to be deleted
    for (String f : indexDir.listAll()) {
      for (Map<String, Object> fileInfo : filesToDownload) {
        if (f.equals(fileInfo.get(NAME))) {
          String filename = (String) fileInfo.get(NAME);
          long size = (Long) fileInfo.get(SIZE);
          CompareResult compareResult =
              compareFile(indexDir, filename, size, (Long) fileInfo.get(CHECKSUM));
          if (!compareResult.equal || filesToAlwaysDownloadIfNoChecksums(f, size, compareResult)) {
            filesTobeDeleted.add(f);
            clearedSpace += size;
          } else {
            /*this file will not be downloaded*/
            actualSpaceReqd -= size;
          }
        }
      }
    }
    if (usableDiskSpace > getApproxTotalSpaceReqd(actualSpaceReqd)) {
      // after considering the files actually available locally we really don't need to do any
      // delete
      return;
    }
    log.info(
        "This disk does not have enough space to download the index from leader. So cleaning up the local index. "
            + " This may lead to loss of data/or node if index replication fails in between");
    // now we should disable searchers and index writers because this core will not have all the
    // required files
    this.clearLocalIndexFirst = true;
    this.solrCore.searchEnabled = false;
    this.solrCore.indexEnabled = false;
    solrCore.getDirectoryFactory().doneWithDirectory(indexDir);
    solrCore.deleteNonSnapshotIndexFiles(indexDirPath);
    this.solrCore.closeSearcher();
    assert testWait.getAsBoolean();
    solrCore.getUpdateHandler().getSolrCoreState().closeIndexWriter(this.solrCore, false);
    for (String f : filesTobeDeleted) {
      try {
        indexDir.deleteFile(f);
      } catch (FileNotFoundException | NoSuchFileException e) {
        // no problem , it was deleted by someone else
      }
    }
  }

  static boolean filesToAlwaysDownloadIfNoChecksums(
      String filename, long size, CompareResult compareResult) {
    // without checksums to compare, we always download .si, .liv, segments_N,
    // and any very small files
    return !compareResult.checkSummed
        && (filename.endsWith(".si")
            || filename.endsWith(".liv")
            || filename.startsWith("segments_")
            || size < _100K);
  }

  protected static class CompareResult {
    boolean equal = false;
    boolean checkSummed = false;
  }

  protected static CompareResult compareFile(
      Directory indexDir, String filename, Long backupIndexFileLen, Long backupIndexFileChecksum) {
    CompareResult compareResult = new CompareResult();
    try {
      try (final IndexInput indexInput = indexDir.openInput(filename, IOContext.READONCE)) {
        long indexFileLen = indexInput.length();
        long indexFileChecksum = 0;

        if (backupIndexFileChecksum != null) {
          try {
            indexFileChecksum = CodecUtil.retrieveChecksum(indexInput);
            compareResult.checkSummed = true;
          } catch (Exception e) {
            log.warn("Could not retrieve checksum from file.", e);
          }
        }

        if (!compareResult.checkSummed) {
          // we don't have checksums to compare

          if (indexFileLen == backupIndexFileLen) {
            compareResult.equal = true;
            return compareResult;
          } else {
            log.info(
                "File {} did not match. expected length is {} and actual length is {}",
                filename,
                backupIndexFileLen,
                indexFileLen);
            compareResult.equal = false;
            return compareResult;
          }
        }

        // we have checksums to compare

        if (indexFileLen == backupIndexFileLen && indexFileChecksum == backupIndexFileChecksum) {
          compareResult.equal = true;
          return compareResult;
        } else {
          log.warn(
              "File {} did not match. expected checksum is {} and actual is checksum {}. "
                  + "expected length is {} and actual length is {}",
              filename,
              backupIndexFileChecksum,
              indexFileChecksum,
              backupIndexFileLen,
              indexFileLen);
          compareResult.equal = false;
          return compareResult;
        }
      }
    } catch (NoSuchFileException | FileNotFoundException e) {
      compareResult.equal = false;
      return compareResult;
    } catch (IOException e) {
      log.error("Could not read file {}. Downloading it again", filename, e);
      compareResult.equal = false;
      return compareResult;
    }
  }

  /**
   * Returns true if the file exists (can be opened), false if it cannot be opened, and (unlike
   * Java's File.exists) throws IOException if there's some unexpected error.
   */
  private static boolean slowFileExists(Directory dir, String fileName) throws IOException {
    try {
      dir.openInput(fileName, IOContext.DEFAULT).close();
      return true;
    } catch (NoSuchFileException | FileNotFoundException e) {
      return false;
    }
  }

  /**
   * All the files which are common between leader and follower must have same size and same
   * checksum else we assume they are not compatible (stale).
   *
   * @return true if the index is stale, and we need to download a fresh copy, false otherwise.
   * @throws IOException if low level io error
   */
  private boolean isIndexStale(Directory dir) throws IOException {
    for (Map<String, Object> file : filesToDownload) {
      String filename = (String) file.get(NAME);
      Long length = (Long) file.get(SIZE);
      Long checksum = (Long) file.get(CHECKSUM);
      if (slowFileExists(dir, filename)) {
        if (checksum != null) {
          if (!(compareFile(dir, filename, length, checksum).equal)) {
            // file exists and size or checksum is different, therefore we must download it again
            return true;
          }
        } else {
          if (length != dir.fileLength(filename)) {
            log.warn(
                "File {} did not match. expected length is {} and actual length is {}",
                filename,
                length,
                dir.fileLength(filename));
            return true;
          }
        }
      }
    }
    return false;
  }

  /** Copy a file by the File#renameTo() method. If it fails, it is considered a failure */
  private boolean moveAFile(Directory tmpIdxDir, Directory indexDir, String fname) {
    log.debug("Moving file: {}", fname);
    boolean success = false;
    try {
      if (slowFileExists(indexDir, fname)) {
        log.warn("Cannot complete replication attempt because file already exists: {}", fname);

        // we fail - we downloaded the files we need, if we can't move one in, we can't
        // count on the correct index
        return false;
      }
    } catch (IOException e) {
      log.error("could not check if a file exists", e);
      return false;
    }
    try {
      solrCore
          .getDirectoryFactory()
          .move(tmpIdxDir, indexDir, fname, DirectoryFactory.IOCONTEXT_NO_CACHE);
      success = true;
    } catch (IOException e) {
      log.error("Could not move file", e);
    }
    return success;
  }

  /**
   * Copy all index files from the temp index dir to the actual index. The segments_N file is copied
   * last.
   */
  private boolean moveIndexFiles(Directory tmpIdxDir, Directory indexDir) {
    if (log.isDebugEnabled()) {
      try {
        if (log.isInfoEnabled()) {
          log.info("From dir files: {}", Arrays.asList(tmpIdxDir.listAll()));
          log.info("To dir files: {}", Arrays.asList(indexDir.listAll())); // nowarn
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    String segmentsFile = null;
    for (Map<String, Object> f : filesDownloaded) {
      String fname = (String) f.get(NAME);
      // the segments file must be copied last
      // or else if there is a failure in between the
      // index will be corrupted
      if (fname.startsWith("segments_")) {
        // The segments file must be copied in the end
        // Otherwise , if the copy fails index ends up corrupted
        segmentsFile = fname;
        continue;
      }
      if (!moveAFile(tmpIdxDir, indexDir, fname)) return false;
    }
    // copy the segments file last
    if (segmentsFile != null) {
      if (!moveAFile(tmpIdxDir, indexDir, segmentsFile)) return false;
    }
    return true;
  }

  /** Make file list TODO: return the stream directly */
  private List<Path> makeTmpConfDirFileList(Path dir) {
    try {
      return Files.walk(dir).filter(Files::isRegularFile).collect(Collectors.toList());
    } catch (IOException e) {
      log.warn("Could not walk file tree", e);
      return Collections.emptyList();
    }
  }

  /**
   * The conf files are copied to the tmp dir to the conf dir. A backup of the old file is
   * maintained
   */
  private void copyTmpConfFiles2Conf(Path tmpconfDir) {
    boolean status = false;
    Path confPath = solrCore.getResourceLoader().getConfigPath();
    int numTempPathElements = tmpconfDir.getNameCount();
    for (Path path : makeTmpConfDirFileList(tmpconfDir)) {
      Path oldPath = confPath.resolve(path.subpath(numTempPathElements, path.getNameCount()));

      try {
        Files.createDirectories(oldPath.getParent());
      } catch (IOException e) {
        throw new SolrException(
            ErrorCode.SERVER_ERROR, "Unable to mkdirs: " + oldPath.getParent(), e);
      }
      if (Files.exists(oldPath)) {
        try {
          Path backupFile =
              oldPath.resolveSibling(
                  oldPath.getFileName()
                      + "."
                      + getDateAsStr(new Date(Files.getLastModifiedTime(oldPath).toMillis())));
          Files.move(oldPath, backupFile);
        } catch (Exception e) {
          throw new SolrException(
              SolrException.ErrorCode.SERVER_ERROR, "Unable to backup old file: " + oldPath, e);
        }
      }
      try {
        Files.move(path, oldPath);
      } catch (IOException e) {
        throw new SolrException(
            ErrorCode.SERVER_ERROR, "Unable to rename: " + path + " to: " + oldPath, e);
      }
    }
  }

  private String getDateAsStr(Date d) {
    return new SimpleDateFormat(SnapShooter.DATE_FMT, Locale.ROOT).format(d);
  }

  private final Map<String, ReplicationHandler.FileInfo> confFileInfoCache = new HashMap<>();

  /**
   * The local conf files are compared with the conf files in the leader. If they are same (by
   * checksum) do not copy.
   *
   * @param confFilesToDownload The list of files obtained from leader
   * @return a list of configuration files which have changed on the leader and need to be
   *     downloaded.
   */
  private Collection<Map<String, Object>> getModifiedConfFiles(
      List<Map<String, Object>> confFilesToDownload) {
    if (confFilesToDownload == null || confFilesToDownload.isEmpty())
      return Collections.emptyList();
    // build a map with alias/name as the key
    Map<String, Map<String, Object>> nameVsFile = new HashMap<>();
    NamedList<String> names = new NamedList<>();
    for (Map<String, Object> map : confFilesToDownload) {
      // if alias is present that is the name the file may have in the follower
      String name = (String) (map.get(ALIAS) == null ? map.get(NAME) : map.get(ALIAS));
      nameVsFile.put(name, map);
      names.add(name, null);
    }
    // get the details of the local conf files with the same alias/name
    List<FileMetaData> localFilesInfo =
        replicationHandler.getConfFileInfoFromCache(names, confFileInfoCache);
    // compare their size/checksum to see if
    for (FileMetaData fileInfo : localFilesInfo) {
      String name = fileInfo.name;
      Map<String, Object> m = nameVsFile.get(name);
      if (m == null) continue; // the file is not even present locally (so must be downloaded)
      if (m.get(CHECKSUM).equals(fileInfo.checksum)) {
        nameVsFile.remove(name); // checksums are same so the file need not be downloaded
      }
    }
    return nameVsFile.isEmpty() ? Collections.emptyList() : nameVsFile.values();
  }

  static boolean delTree(Path dir) {
    try {
      org.apache.lucene.util.IOUtils.rm(dir);
      return true;
    } catch (IOException e) {
      log.warn("Unable to delete directory : {}", dir, e);
      return false;
    }
  }

  /** Stops the ongoing fetch */
  void abortFetch() {
    stop = true;
  }

  @SuppressForbidden(reason = "Need currentTimeMillis for debugging/stats")
  private void markReplicationStart() {
    replicationTimer = new RTimer();
    replicationStartTimeStamp = new Date();
  }

  private void markReplicationStop() {
    replicationStartTimeStamp = null;
    replicationTimer = null;
  }

  Date getReplicationStartTimeStamp() {
    return replicationStartTimeStamp;
  }

  long getReplicationTimeElapsed() {
    long timeElapsed = 0;
    if (replicationStartTimeStamp != null)
      timeElapsed =
          TimeUnit.SECONDS.convert((long) replicationTimer.getTime(), TimeUnit.MILLISECONDS);
    return timeElapsed;
  }

  List<Map<String, Object>> getConfFilesToDownload() {
    // make a copy first because it can be null later
    List<Map<String, Object>> tmp = confFilesToDownload;
    // create a new instance. or else iterator may fail
    return tmp == null ? Collections.emptyList() : new ArrayList<>(tmp);
  }

  List<Map<String, Object>> getConfFilesDownloaded() {
    // make a copy first because it can be null later
    List<Map<String, Object>> tmp = confFilesDownloaded;
    // NOTE: it's safe to make a copy of a SynchronizedCollection(ArrayList)
    return tmp == null ? Collections.emptyList() : new ArrayList<>(tmp);
  }

  List<Map<String, Object>> getFilesToDownload() {
    // make a copy first because it can be null later
    List<Map<String, Object>> tmp = filesToDownload;
    return tmp == null ? Collections.emptyList() : new ArrayList<>(tmp);
  }

  List<Map<String, Object>> getFilesDownloaded() {
    List<Map<String, Object>> tmp = filesDownloaded;
    return tmp == null ? Collections.emptyList() : new ArrayList<>(tmp);
  }

  // TODO: currently does not reflect conf files
  Map<String, Object> getCurrentFile() {
    Map<String, Object> tmp = currentFile;
    DirectoryFileFetcher tmpFileFetcher = dirFileFetcher;
    if (tmp == null) return null;
    tmp = new HashMap<>(tmp);
    if (tmpFileFetcher != null) tmp.put("bytesDownloaded", tmpFileFetcher.getBytesDownloaded());
    return tmp;
  }

  private static class ReplicationHandlerException extends InterruptedException {
    public ReplicationHandlerException(String message) {
      super(message);
    }
  }

  private interface FileInterface {
    public void sync() throws IOException;

    public void write(byte[] buf, int packetSize) throws IOException;

    public void close() throws Exception;

    public void delete() throws Exception;
  }

  /**
   * The class acts as a client for ReplicationHandler.FileStream. It understands the protocol of
   * wt=filestream
   *
   * <p>see org.apache.solr.handler.admin.api.ReplicationAPIBase.DirectoryFileStream
   */
  private class FileFetcher {
    private final FileInterface file;
    private boolean includeChecksum = true;
    private final String fileName;
    private final String saveAs;
    private final String solrParamOutput;
    private final Long indexGen;

    private final long size;
    private long bytesDownloaded = 0;
    private byte[] buf;
    private final Checksum checksum;
    private int errorCount = 0;
    private boolean aborted = false;

    FileFetcher(
        FileInterface file,
        Map<String, Object> fileDetails,
        String saveAs,
        String solrParamOutput,
        long latestGen) {
      this.file = file;
      this.fileName = (String) fileDetails.get(NAME);
      this.size = (Long) fileDetails.get(SIZE);
      buf = new byte[(int) Math.min(this.size, ReplicationAPIBase.PACKET_SZ)];
      this.solrParamOutput = solrParamOutput;
      this.saveAs = saveAs;
      indexGen = latestGen;
      if (includeChecksum) {
        checksum = new Adler32();
      } else {
        checksum = null;
      }
    }

    public long getBytesDownloaded() {
      return bytesDownloaded;
    }

    /** The main method which downloads file */
    public void fetchFile() throws Exception {
      bytesDownloaded = 0;
      try {
        fetch();
      } catch (Exception e) {
        if (!aborted) {
          IndexFetcher.log.error("Error fetching file, doing one retry...", e);
          // one retry
          fetch();
        } else {
          throw e;
        }
      }
    }

    private void fetch() throws Exception {
      try {
        while (true) {
          try (FastInputStream fis = getStream()) {
            int result;
            // fetch packets one by one in a single request
            result = fetchPackets(fis);
            if (result == 0 || result == NO_CONTENT) {
              return;
            }
            // if there is an error continue. But continue from the point where it got broken
          }
        }
      } finally {
        cleanup();
        // if cleanup succeeds, and the file is downloaded fully, then do a fsync.
        fsyncService.execute(
            () -> {
              try {
                file.sync();
              } catch (IOException | AlreadyClosedException e) {
                fsyncException = e;
              }
            });
      }
    }

    private int fetchPackets(FastInputStream fis) throws Exception {
      byte[] intbytes = new byte[4];
      byte[] longbytes = new byte[8];
      try {
        while (true) {
          if (fis.peek() == -1) {
            if (bytesDownloaded == 0) {
              log.warn("No content received for file: {}", fileName);
              return NO_CONTENT;
            }
            return 0;
          }
          if (stop) {
            stop = false;
            aborted = true;
            throw new ReplicationHandlerException("User aborted replication");
          }
          long checkSumServer = -1;

          fis.readFully(intbytes);
          // read the size of the packet
          int packetSize = readInt(intbytes);
          if (packetSize <= 0) {
            continue;
          }
          // TODO consider recoding the remaining logic to not use/need buf[]; instead use the
          // internal buffer of fis
          if (buf.length < packetSize) {
            // This shouldn't happen since sender should use PACKET_SZ and we init the buf based on
            // that too
            buf = new byte[packetSize];
          }
          if (checksum != null) {
            // read the checksum
            fis.readFully(longbytes);
            checkSumServer = readLong(longbytes);
          }
          // then read the packet of bytes
          fis.readFully(buf, 0, packetSize);
          // compare the checksum as sent from the leader
          if (includeChecksum) {
            checksum.reset();
            checksum.update(buf, 0, packetSize);
            long checkSumClient = checksum.getValue();
            if (checkSumClient != checkSumServer) {
              log.error("Checksum not matched between client and server for file: {}", fileName);
              // if checksum is wrong it is a problem return (there doesn't seem to be a retry in
              // this case.)
              return 1;
            }
          }
          // if everything is fine, write down the packet to the file
          file.write(buf, packetSize);
          bytesDownloaded += packetSize;
          log.debug("Fetched and wrote {} bytes of file: {}", bytesDownloaded, fileName);
          // errorCount is always set to zero after a successful packet
          errorCount = 0;
        }
      } catch (ReplicationHandlerException e) {
        throw e;
      } catch (Exception e) {
        log.warn(
            "Error in fetching file: {} (downloaded {} of {} bytes)",
            fileName,
            bytesDownloaded,
            size,
            e);
        // for any failure, increment the error count
        errorCount++;
        // if it fails for the same packet for MAX_RETRIES fail and come out
        if (errorCount > MAX_RETRIES) {
          throw new SolrException(
              SolrException.ErrorCode.SERVER_ERROR,
              "Failed to fetch file: "
                  + fileName
                  + " (downloaded "
                  + bytesDownloaded
                  + " of "
                  + size
                  + " bytes"
                  + ", error count: "
                  + errorCount
                  + " > "
                  + MAX_RETRIES
                  + ")",
              e);
        }
        return ERR;
      }
    }

    /**
     * The web container flushes the data only after it fills the buffer size. So, all data has to
     * be read as readFully() otherwise it fails. So read everything as bytes and then extract an
     * integer out of it
     */
    private int readInt(byte[] b) {
      return (((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff));
    }

    /** Same as above but to read longs from a byte array */
    private long readLong(byte[] b) {
      return (((long) (b[0] & 0xff)) << 56)
          | (((long) (b[1] & 0xff)) << 48)
          | (((long) (b[2] & 0xff)) << 40)
          | (((long) (b[3] & 0xff)) << 32)
          | (((long) (b[4] & 0xff)) << 24)
          | ((b[5] & 0xff) << 16)
          | ((b[6] & 0xff) << 8)
          | ((b[7] & 0xff));
    }

    /** cleanup everything */
    private void cleanup() {
      try {
        file.close();
      } catch (Exception e) {
        /* no-op */
        log.error("Error closing file: {}", this.saveAs, e);
      }
      if (bytesDownloaded != size) {
        // if the download is not complete then
        // delete the file being downloaded
        try {
          file.delete();
        } catch (Exception e) {
          log.error("Error deleting file: {}", this.saveAs, e);
        }
        // if the failure is due to a user abort it is returned normally else an exception is thrown
        if (!aborted)
          throw new SolrException(
              SolrException.ErrorCode.SERVER_ERROR,
              "Unable to download "
                  + fileName
                  + " completely. Downloaded "
                  + bytesDownloaded
                  + "!="
                  + size);
      }
    }

    /** Open a new stream using HttpClient */
    private FastInputStream getStream() throws IOException {
      ModifiableSolrParams params = new ModifiableSolrParams();

      // the method is command=filecontent
      params.set(COMMAND, CMD_GET_FILE);
      params.set(GENERATION, Long.toString(indexGen));
      params.set(CommonParams.QT, ReplicationHandler.PATH);
      // add the version to download. This is used to reserve the download
      params.set(solrParamOutput, fileName);
      if (useInternalCompression) {
        params.set(COMPRESSION, "true");
      }
      // use checksum
      if (this.includeChecksum) {
        params.set(CHECKSUM, true);
      }
      // wt=filestream this is a custom protocol
      params.set(CommonParams.WT, FILE_STREAM);
      // This happens if there is a failure there is a retry. the offset=<sizedownloaded> ensures
      // that the server starts from the offset
      if (bytesDownloaded > 0) {
        params.set(OFFSET, Long.toString(bytesDownloaded));
      }

      NamedList<?> response;
      InputStream is = null;
      // TODO use shardhandler
      try {
        QueryRequest req = new QueryRequest(params);
        req.setResponseParser(new InputStreamResponseParser(FILE_STREAM));
        if (useExternalCompression) req.addHeader("Accept-Encoding", "gzip");
        response = solrClient.requestWithBaseUrl(leaderBaseUrl, leaderCoreName, req).getResponse();
        final var responseStatus = (Integer) response.get("responseStatus");
        is = (InputStream) response.get("stream");

        if (responseStatus != 200) {
          final var errorMsg =
              String.format(
                  Locale.ROOT,
                  "Unexpected status code [%d] when downloading file [%s].",
                  responseStatus,
                  fileName);
          closeStreamAndBuildIOE(is, errorMsg, null);
        }

        if (useInternalCompression) {
          is = new InflaterInputStream(is);
        }
        return new FastInputStream(is);
      } catch (Exception e) {
        final var ioe = closeStreamAndBuildIOE(is, "Could not download file '" + fileName + "'", e);
        throw ioe;
      }
    }

    private IOException closeStreamAndBuildIOE(
        InputStream is, String exceptionMessage, Exception e) {
      IOUtils.closeQuietly(is);
      if (e != null) {
        return new IOException(exceptionMessage, e);
      }
      return new IOException(exceptionMessage);
    }
  }

  private static class DirectoryFile implements FileInterface {
    private final String saveAs;
    private Directory copy2Dir;
    private IndexOutput outStream;

    DirectoryFile(Directory tmpIndexDir, String saveAs) throws IOException {
      this.saveAs = saveAs;
      this.copy2Dir = tmpIndexDir;
      outStream = copy2Dir.createOutput(this.saveAs, DirectoryFactory.IOCONTEXT_NO_CACHE);
    }

    @Override
    public void sync() throws IOException {
      copy2Dir.sync(Collections.singleton(saveAs));
    }

    @Override
    public void write(byte[] buf, int packetSize) throws IOException {
      outStream.writeBytes(buf, 0, packetSize);
    }

    @Override
    public void close() throws Exception {
      outStream.close();
    }

    @Override
    public void delete() throws Exception {
      copy2Dir.deleteFile(saveAs);
    }
  }

  protected class DirectoryFileFetcher extends FileFetcher {
    DirectoryFileFetcher(
        Directory tmpIndexDir,
        Map<String, Object> fileDetails,
        String saveAs,
        String solrParamOutput,
        long latestGen)
        throws IOException {
      super(
          new DirectoryFile(tmpIndexDir, saveAs), fileDetails, saveAs, solrParamOutput, latestGen);
    }
  }

  private static class LocalFsFile implements FileInterface {

    FileChannel fileChannel;
    private FileOutputStream fileOutputStream;
    Path file;

    LocalFsFile(Path dir, String saveAs) throws IOException {
      this.file = dir.resolve(saveAs);

      Path parentDir = this.file.getParent();
      if (Files.notExists(parentDir)) {
        try {
          Files.createDirectories(parentDir);
        } catch (Exception e) {
          throw new SolrException(
              SolrException.ErrorCode.SERVER_ERROR,
              "Failed to create (sub)directory for file: " + saveAs);
        }
      }

      this.fileOutputStream = new FileOutputStream(file.toFile());
      this.fileChannel = this.fileOutputStream.getChannel();
    }

    @Override
    public void sync() throws IOException {
      org.apache.lucene.util.IOUtils.fsync(file, false);
    }

    @Override
    public void write(byte[] buf, int packetSize) throws IOException {
      fileChannel.write(ByteBuffer.wrap(buf, 0, packetSize));
    }

    @Override
    public void close() throws Exception {
      // close the FileOutputStream (which also closes the Channel)
      fileOutputStream.close();
    }

    @Override
    public void delete() throws Exception {
      Files.delete(file);
    }
  }

  protected class LocalFsFileFetcher extends FileFetcher {
    LocalFsFileFetcher(
        Path dir,
        Map<String, Object> fileDetails,
        String saveAs,
        String solrParamOutput,
        long latestGen)
        throws IOException {
      super(new LocalFsFile(dir, saveAs), fileDetails, saveAs, solrParamOutput, latestGen);
    }
  }

  NamedList<Object> getDetails() throws IOException, SolrServerException {
    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set(COMMAND, CMD_DETAILS);
    params.set("follower", false);
    params.set(CommonParams.QT, ReplicationHandler.PATH);

    QueryRequest request = new QueryRequest(params);
    // TODO use shardhandler
    return solrClient.requestWithBaseUrl(leaderBaseUrl, leaderCoreName, request).getResponse();
  }

  public void destroy() {
    abortFetch();
    IOUtils.closeQuietly(solrClient);
  }

  String getLeaderCoreUrl() {
    return leaderCoreUrl;
  }

  private static final int MAX_RETRIES = 5;

  private static final int NO_CONTENT = 1;

  private static final int ERR = 2;

  public static final String REPLICATION_PROPERTIES = "replication.properties";

  static final String INDEX_REPLICATED_AT = "indexReplicatedAt";

  static final String TIMES_INDEX_REPLICATED = "timesIndexReplicated";

  static final String CLEARED_LOCAL_IDX = "clearedLocalIndexFirst";

  static final String CONF_FILES_REPLICATED = "confFilesReplicated";

  static final String CONF_FILES_REPLICATED_AT = "confFilesReplicatedAt";

  static final String TIMES_CONFIG_REPLICATED = "timesConfigReplicated";

  static final String LAST_CYCLE_BYTES_DOWNLOADED = "lastCycleBytesDownloaded";

  static final String TIMES_FAILED = "timesFailed";

  static final String REPLICATION_FAILED_AT = "replicationFailedAt";

  static final String PREVIOUS_CYCLE_TIME_TAKEN = "previousCycleTimeInSeconds";

  static final String INDEX_REPLICATED_AT_LIST = "indexReplicatedAtList";

  static final String REPLICATION_FAILED_AT_LIST = "replicationFailedAtList";
}
