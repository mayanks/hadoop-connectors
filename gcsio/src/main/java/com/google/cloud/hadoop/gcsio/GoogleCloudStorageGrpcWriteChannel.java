/*
 * Copyright 2019 Google Inc. All Rights Reserved.
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

package com.google.cloud.hadoop.gcsio;

import com.google.cloud.hadoop.util.AsyncWriteChannelOptions;
import com.google.cloud.hadoop.util.BaseAbstractGoogleAsyncWriteChannel;
import com.google.google.storage.v1.InsertObjectRequest;
import com.google.google.storage.v1.InsertObjectSpec;
import com.google.google.storage.v1.Object;
import com.google.google.storage.v1.StartResumableWriteRequest;
import com.google.google.storage.v1.StartResumableWriteResponse;
import com.google.google.storage.v1.StorageObjectsGrpc.StorageObjectsStub;
import com.google.protobuf.ByteString;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

/** Implements WritableByteChannel to provide write access to GCS via gRPC. */
public final class GoogleCloudStorageGrpcWriteChannel
    extends BaseAbstractGoogleAsyncWriteChannel<Object>
    implements GoogleCloudStorageItemInfo.Provider {

  private final Object object;
  private final ObjectWriteConditions writeConditions;
  private final StorageObjectsStub stub;

  private GoogleCloudStorageItemInfo completedItemInfo = null;

  public GoogleCloudStorageGrpcWriteChannel(
      ExecutorService threadPool,
      StorageObjectsStub stub,
      String bucketName,
      String objectName,
      AsyncWriteChannelOptions options,
      ObjectWriteConditions writeConditions,
      Map<String, String> objectMetadata,
      String contentType) {
    super(threadPool, options);
    this.stub = stub;
    this.writeConditions = writeConditions;
    this.object =
        Object.newBuilder()
            .setName(objectName)
            .setBucket(bucketName)
            .setContentType(contentType)
            .putAllMetadata(objectMetadata)
            .build();
  }

  @Override
  public void handleResponse(Object response) {
    // TODO(julianandrews): Populate this properly.
    completedItemInfo =
        new GoogleCloudStorageItemInfo(
            new StorageResourceId(object.getBucket(), object.getName()), 0, 0, 0, "", "");
  }

  @Override
  public void startUpload(PipedInputStream pipeSource) throws IOException {
    // Given that the two ends of the pipe must operate asynchronous relative
    // to each other, we need to start the upload operation on a separate thread.
    uploadOperation = threadPool.submit(new UploadOperation(pipeSource));
  }

  private class UploadOperation implements Callable<Object> {
    // Read end of the pipe.
    private final BufferedInputStream pipeSource;

    public UploadOperation(InputStream pipeSource) {
      // Buffer the input stream by 1 byte so we can peek ahead for the end of the stream.
      this.pipeSource = new BufferedInputStream(pipeSource, 1);
    }

    @Override
    public Object call() throws IOException, InterruptedException {
      // Try-with-resource will close this end of the pipe so that
      // the writer at the other end will not hang indefinitely.
      try (InputStream uploadPipeSource = pipeSource) {
        return doResumableUpload();
      }
    }

    private Object doResumableUpload() throws IOException, InterruptedException {
      String uploadId = getUploadId();

      InsertChunkResponseObserver responseObserver;
      int writeOffset = 0;
      do {
        responseObserver = new InsertChunkResponseObserver(uploadId, writeOffset);
        stub.insert(responseObserver);
        responseObserver.done.await();

        writeOffset += responseObserver.chunkBytesWritten;
      } while (!responseObserver.isFinished());

      if (responseObserver.hasError()) {
        throw new IOException("Insert failed", responseObserver.getError());
      }

      return responseObserver.getResponse();
    }

    // TODO(julianandrews): Keep a buffer for the chunk data and resume on recoverable errors.
    /** Handler for responses from the Insert streaming RPC. */
    private class InsertChunkResponseObserver
        implements ClientResponseObserver<InsertObjectRequest, Object> {
      private static final int MAX_BYTES_PER_REQUEST = 2 * 1024 * 1024;

      private final int chunkStart;
      private final String uploadId;
      private volatile boolean streamFinished = false;
      // The last error to occur during the streaming RPC. Present only on error.
      private Throwable error;
      // The response from the server, populated at the end of a successful streaming RPC.
      private Object response;

      // CountDownLatch tracking completion of the streaming RPC. Set on error, or once the request
      // stream is closed.
      final CountDownLatch done = new CountDownLatch(1);

      // The number of bytes written in this streaming RPC.
      volatile int chunkBytesWritten = 0;

      InsertChunkResponseObserver(String uploadId, int chunkStart) {
        this.uploadId = uploadId;
        this.chunkStart = chunkStart;
      }

      @Override
      public void beforeStart(final ClientCallStreamObserver<InsertObjectRequest> requestObserver) {
        requestObserver.setOnReadyHandler(
            new Runnable() {
              @Override
              public void run() {
                // Protect against calls to setUploadChunkSize mid-request.
                final int chunkSize = uploadChunkSize;

                if (chunkFinished(chunkSize)) {
                  // onReadyHandler may be called after we've closed the request half of the stream.
                  return;
                }

                ByteString data;
                try {
                  data = getRequestData(chunkSize);
                } catch (IOException e) {
                  error = e;
                  return;
                }

                InsertObjectRequest.Builder requestBuilder =
                    InsertObjectRequest.newBuilder()
                        .setUploadId(uploadId)
                        .setWriteOffset(chunkStart + chunkBytesWritten)
                        .setFinishWrite(streamFinished);
                if (data.size() > 0) {
                  // TODO(julianandrews): Calculate and set crc32c checksums.
                  requestBuilder.setData(data);
                  chunkBytesWritten += data.size();
                }
                requestObserver.onNext(requestBuilder.build());

                if (chunkFinished(chunkSize)) {
                  // Close the request half of the streaming RPC.
                  requestObserver.onCompleted();
                }
              }

              private ByteString getRequestData(int chunkSize) throws IOException {
                // Read data from the input stream.
                byte[] data = new byte[MAX_BYTES_PER_REQUEST];
                int bytesToRead = Math.min(chunkSize - chunkBytesWritten, MAX_BYTES_PER_REQUEST);

                // The API supports only non-final requests which are multiples of 256KiB, and
                // Blobstore performs better with larger requests, so block until we have a full
                // MAX_BYTES_PER_REQUEST to send or the stream is empty.
                int bytesRead = 0;
                int lastReadBytes = 0;
                while (bytesRead < bytesToRead && lastReadBytes != -1) {
                  lastReadBytes = pipeSource.read(data, bytesRead, bytesToRead - bytesRead);
                  if (lastReadBytes > 0) {
                    bytesRead += lastReadBytes;
                  }
                }

                // Set streamFinished if there is no more data, looking ahead 1
                // byte in the buffer if neccessary.
                pipeSource.mark(1);
                streamFinished = lastReadBytes == -1 || pipeSource.read() == -1;
                pipeSource.reset();

                return ByteString.copyFrom(data, 0, bytesToRead);
              }

              private boolean chunkFinished(int chunkSize) {
                return streamFinished || chunkBytesWritten >= chunkSize;
              }
            });
      }

      public boolean hasResponse() {
        return response != null;
      }

      public Object getResponse() {
        if (response == null) {
          throw new IllegalStateException("Response not present.");
        }
        return response;
      }

      public boolean hasError() {
        return error != null;
      }

      public Throwable getError() {
        if (error == null) {
          throw new IllegalStateException("Error not present.");
        }
        return error;
      }

      public boolean isFinished() {
        return streamFinished || hasError();
      }

      @Override
      public void onNext(Object response) {
        this.response = response;
      }

      @Override
      public void onError(Throwable t) {
        error = t;
        done.countDown();
      }

      @Override
      public void onCompleted() {
        done.countDown();
      }
    }

    /** Send a StartResumableWriteRequest and return the uploadId of the resumable write. */
    private String getUploadId() throws InterruptedException, IOException {
      // TODO(julianandrews): set preconditions and user project
      StartResumableWriteRequest request =
          StartResumableWriteRequest.newBuilder()
              .setInsertObjectSpec(InsertObjectSpec.newBuilder().setResource(object))
              .build();

      StartResumableWriteResponseObserver responseObserver =
          new StartResumableWriteResponseObserver();

      stub.startResumableWrite(request, responseObserver);
      responseObserver.done.await();

      if (responseObserver.hasError()) {
        throw new IOException(
            "StartResumableWriteRequest failed", responseObserver.getError().getCause());
      }

      return responseObserver.getResponse().getUploadId();
    }

    /** Handler for responses from the StartResumableWrite RPC. */
    private class StartResumableWriteResponseObserver
        implements StreamObserver<StartResumableWriteResponse> {
      // The response from the server, populated at the end of a successful RPC.
      private StartResumableWriteResponse response;

      // The last error to occur during the RPC. Present only on error.
      private Throwable error;

      // CountDownLatch tracking completion of the RPC.
      final CountDownLatch done = new CountDownLatch(1);

      public boolean hasResponse() {
        return response != null;
      }

      public StartResumableWriteResponse getResponse() {
        if (response == null) {
          throw new IllegalStateException("Response not present.");
        }
        return response;
      }

      public boolean hasError() {
        return error != null;
      }

      public Throwable getError() {
        if (error == null) {
          throw new IllegalStateException("Error not present.");
        }
        return error;
      }

      @Override
      public void onNext(StartResumableWriteResponse response) {
        this.response = response;
      }

      @Override
      public void onError(Throwable t) {
        error = t;
        done.countDown();
      }

      @Override
      public void onCompleted() {
        done.countDown();
      }
    }
  }

  /**
   * Returns non-null only if close() has been called and the underlying object has been
   * successfully committed.
   */
  @Override
  public GoogleCloudStorageItemInfo getItemInfo() {
    return this.completedItemInfo;
  }
}
