/*
 * Copyright 2021 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.gcsio;

/** Statistics which are collected in GCSIO. */
public enum GoogleCloudStorageStatistics {
  // number of objects deleted
  OBJECT_DELETE_OBJECTS,

  ACTION_HTTP_GET_REQUEST,

  ACTION_HTTP_HEAD_REQUEST,

  ACTION_HTTP_PUT_REQUEST,

  ACTION_HTTP_POST_REQUEST,

  ACTION_HTTP_PATCH_REQUEST,

  ACTION_HTTP_DELETE_REQUEST,

  ACTION_HTTP_GET_REQUEST_FAILURES,

  ACTION_HTTP_HEAD_REQUEST_FAILURES,

  ACTION_HTTP_PUT_REQUEST_FAILURES,

  ACTION_HTTP_POST_REQUEST_FAILURES,

  ACTION_HTTP_PATCH_REQUEST_FAILURES,

  ACTION_HTTP_DELETE_REQUEST_FAILURES
}