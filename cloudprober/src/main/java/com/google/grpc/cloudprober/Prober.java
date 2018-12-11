/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.grpc.cloudprober;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.collect.ImmutableList;
import com.google.spanner.v1.SpannerGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.auth.MoreCallCredentials;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Main method of the cloudprober as an entrypoint to execute probes. */
public class Prober {

  private static final String OAUTH_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
  private static final String SPANNER_TARGET = "spanner.googleapis.com";
  private static final Logger logger = Logger.getLogger(Prober.class.getName());

  private Prober() {}

  /** Set the authentication. */
  private static GoogleCredentials getCreds() throws InterruptedException {
    GoogleCredentials creds;
    try {
      creds = GoogleCredentials.getApplicationDefault();
    } catch (Exception e) {
      logger.severe(e.getMessage());
      return null;
    }
    ImmutableList<String> requiredScopes = ImmutableList.of(OAUTH_SCOPE);
    creds = creds.createScoped(requiredScopes);
    return creds;
  }

  private static void excuteSpannerProber(StackdriverUtils util) throws InterruptedException {

    ManagedChannel channel = ManagedChannelBuilder.forAddress(SPANNER_TARGET, 443).build();
    GoogleCredentials creds = getCreds();
    SpannerGrpc.SpannerBlockingStub stub =
        SpannerGrpc.newBlockingStub(channel).withCallCredentials(MoreCallCredentials.from(creds));

    int successCount = 0;
    Map<String, Long> metrics = new HashMap<String, Long>();
    try {
      SpannerProbes.sessionManagementProber(stub, metrics);
    } catch (Exception e) {
      logger.severe(e.getMessage());
      successCount--;
    }
    try {
      SpannerProbes.executeSqlProber(stub, metrics);
    } catch (Exception e) {
      logger.severe(e.getMessage());
      successCount--;
    }
    try {
      SpannerProbes.readProber(stub, metrics);
    } catch (Exception e) {
      logger.severe(e.getMessage());
      successCount--;
    }
    try {
      SpannerProbes.transactionProber(stub, metrics);
    } catch (Exception e) {
      logger.severe(e.getMessage());
      successCount--;
    }
    try {
      SpannerProbes.partitionProber(stub, metrics);
    } catch (Exception e) {
      logger.severe(e.getMessage());
      successCount--;
    }

    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    if (successCount == 0) {
      util.setSuccess(true);
    }
    util.addMetricsDict(metrics);
  }

  public static void main(String[] args) throws InterruptedException {
    logger.info("Start probing..");
    StackdriverUtils util = new StackdriverUtils("Spanner");
    excuteSpannerProber(util);
    util.outputMetrics();
  }
}
