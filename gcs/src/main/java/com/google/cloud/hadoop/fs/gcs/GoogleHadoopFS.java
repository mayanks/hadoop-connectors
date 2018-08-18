/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.fs.gcs;

import com.google.common.base.Preconditions;
import com.google.common.flogger.GoogleLogger;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.EnumSet;
import java.util.StringTokenizer;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.AbstractFileSystem;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.CreateFlag;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileChecksum;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FsServerDefaults;
import org.apache.hadoop.fs.FsStatus;
import org.apache.hadoop.fs.Options.ChecksumOpt;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.util.Progressable;

/**
 * GoogleHadoopFS provides a YARN compatible Abstract File System on top of
 * Google Cloud Storage (GCS).
 *
 * It is implemented as a thin abstraction layer on top of GoogleHadoopFileSystem, but will soon be
 * refactored to share a common base.
 */
public class GoogleHadoopFS extends AbstractFileSystem {

  public static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  // Wrapped GoogleHadoopFileSystem instance
  private GoogleHadoopFileSystem ghfs;

  public GoogleHadoopFS(URI uri, Configuration conf) throws URISyntaxException, IOException {
    this(new GoogleHadoopFileSystem(), uri, conf);
  }

  public GoogleHadoopFS(GoogleHadoopFileSystem ghfs, URI uri, Configuration conf)
      throws URISyntaxException, IOException {
    // AbstractFileSystem requires authority based AbstractFileSystems to have valid ports.
    // true == GoogleHadoopFS requires authorities in URIs.
    // 0 == the fake port passed to AbstractFileSystem.
    super(uri, ghfs.getScheme(), true, 0);
    Preconditions.checkArgument(ghfs != null, "ghfs must not be null");
    this.ghfs = ghfs;
    ghfs.initialize(uri, conf);
  }

  @Override
  public FSDataOutputStream createInternal(
      Path file,
      EnumSet<CreateFlag> flag,
      FsPermission absolutePermission,
      int bufferSize,
      short replication,
      long blockSize,
      Progressable progress,
      ChecksumOpt checksumOpt,
      boolean createParent) throws IOException {
    logger.atFine().log(
        "createInternal: flag: %s, absolutePermission: %s, bufferSize: %s, replication: %s,"
            + "blockSize: %s, progress: %s, checksumOpt: %s, createParent: %s",
        flag,
        absolutePermission,
        bufferSize,
        replication,
        blockSize,
        progress,
        checksumOpt,
        createParent);
    if (!createParent) {
      logger.atFine().log("Ignoring createParent=false. Creating parents anyways.");
    }
    // AbstractFileSystems rely on permission to not overwrite.
    boolean overwriteFile = true;
    return ghfs.create(
        file, absolutePermission, overwriteFile, bufferSize, replication, blockSize, progress);
  }

  @Override
  public int getUriDefaultPort() {
    logger.atFine().log("getUriDefaultPort");
    return ghfs.getDefaultPort();
  }

  /**
   * This is overridden to use GoogleHadoopFileSystem's URI, because AbstractFileSystem appends the
   * default port to the authority.
   */
  @Override
  public URI getUri() {
    return ghfs.getUri();
  }

  /**
   * Follow HDFS conventions except allow for ':' in paths.
   */
  @Override
  public boolean isValidName(String src) {
    StringTokenizer tokens = new StringTokenizer(src, Path.SEPARATOR);
    while (tokens.hasMoreTokens()) {
      String element = tokens.nextToken();
      if (element.equals("..") || element.equals(".")) {
        return false;
      }
    }
    return true;
  }

  /**
   * Only accept valid AbstractFileSystem and GoogleHadoopFileSystem Paths.
   */
  @Override
  public void checkPath(Path path) {
    super.checkPath(path);
    ghfs.checkPath(path);
  }

  // TODO(user): Implement GoogleHadoopFileSystemBase.getServerDefaults(Path)
  @SuppressWarnings("deprecation")
  @Override
  public FsServerDefaults getServerDefaults() throws IOException {
    logger.atFine().log("getServerDefaults");
    return ghfs.getServerDefaults();
  }


  @Override
  public void mkdir(final Path dir, final FsPermission permission, final boolean createParent)
      throws IOException {
    logger.atFine().log(
        "mkdir: dir: %s, permission: %s, createParent %s", dir, permission, createParent);
    if (!createParent) {
      logger.atFine().log("Ignoring createParent=false. Creating parents anyways.");
    }
    ghfs.mkdirs(dir, permission);
  }

  @Override
  public boolean delete(final Path f, final boolean recursive) throws IOException {
    logger.atFine().log("delete");
    return ghfs.delete(f, recursive);
  }

  @Override
  public FSDataInputStream open(final Path f, int bufferSize) throws IOException {
    logger.atFine().log("open");
    return ghfs.open(f, bufferSize);
  }

  @Override
  public boolean setReplication(final Path f, final short replication) throws IOException {
    logger.atFine().log("setReplication");
    return ghfs.setReplication(f, replication);
  }

  @Override
  public void renameInternal(final Path src, final Path dst) throws IOException {
    logger.atFine().log("renameInternal");
    ghfs.rename(src, dst);
  }

  @Override
  public void setPermission(final Path f, final FsPermission permission) throws IOException {
    logger.atFine().log("setPermission");
    ghfs.setPermission(f, permission);
  }

  @Override
  public void setOwner(final Path f, final String username, final String groupname)
      throws IOException {
    logger.atFine().log("setOwner");
    ghfs.setOwner(f, username, groupname);
  }

  @Override
  public void setTimes(final Path f, final long mtime, final long atime) throws IOException {
    logger.atFine().log("setTimes");
    ghfs.setTimes(f, mtime, atime);
  }

  @Override
  public FileChecksum getFileChecksum(final Path f) throws IOException {
    logger.atFine().log("getFileChecksum");
    return ghfs.getFileChecksum(f);
  }

  @Override
  public FileStatus getFileStatus(final Path f) throws IOException {
    logger.atFine().log("getFileStatus");
    return ghfs.getFileStatus(f);
  }

  @Override
  public BlockLocation[] getFileBlockLocations(final Path f, final long start, final long len)
      throws IOException {
    logger.atFine().log("getFileBlockLocations");
    return ghfs.getFileBlockLocations(f, start, len);
  }

  @Override
  public FsStatus getFsStatus() throws IOException {
    logger.atFine().log("getFsStatus");
    return ghfs.getStatus();
  }

  @Override
  public FileStatus[] listStatus(final Path f) throws IOException {
    logger.atFine().log("listStatus");
    return ghfs.listStatus(f);
  }

  @Override
  public void setVerifyChecksum(final boolean verifyChecksum) {
    logger.atFine().log("setVerifyChecksum");
    ghfs.setVerifyChecksum(verifyChecksum);
  }
}
