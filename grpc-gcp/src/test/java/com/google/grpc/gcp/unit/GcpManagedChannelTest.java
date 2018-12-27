/*
 * Copyright 2019 Google LLC
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

package com.google.grpc.gcp;

import static org.junit.Assert.assertEquals;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for GcpManagedChannel. */
@RunWith(JUnit4.class)
public final class GcpManagedChannelTest {

  private static final String TARGET = "www.jenny.com";
  private static final String API_FILE = "src/test/resources/apiconfigtests/apiconfig.json";

  private static final int MAX_CHANNEL = 10;
  private static final int MAX_STREAM = 100;

  private GcpManagedChannel gcpChannel;
  private ManagedChannelBuilder builder;

  /** Close and delete all the channelRefs inside a gcpchannel. */
  private void resetGcpChannel() {
    gcpChannel.shutdownNow();
    gcpChannel.channelRefs.clear();
  }

  @Before
  public void setUpChannel() {
    builder = ManagedChannelBuilder.forAddress(TARGET, 443);
    gcpChannel = new GcpManagedChannel(builder);
  }

  @After
  public void shutdown() throws Exception {
    gcpChannel.shutdownNow();
  }

  @Test
  public void testLoadApiConfig() throws Exception {
    resetGcpChannel();
    gcpChannel = new GcpManagedChannel(builder, API_FILE);
    assertEquals(1, gcpChannel.channelRefs.size());
    assertEquals(10, gcpChannel.getMaxSize());
    assertEquals(1, gcpChannel.getStreamsLowWatermark());
    assertEquals(3, gcpChannel.methodToAffinity.size());
  }

  @Test
  public void testGetChannelRefInitialization() throws Exception {
    // Should have a managedchannel by default.
    assertEquals(1, gcpChannel.channelRefs.size());
    assertEquals(0, gcpChannel.getChannelRef().getAffinityCount());
    // The state of this channel is idle.
    assertEquals(ConnectivityState.IDLE, gcpChannel.getState(false));
    assertEquals(1, gcpChannel.channelRefs.size());
  }

  @Test
  public void testGetChannelRefPickUpSmallest() throws Exception {
    // All channels have max number of streams
    resetGcpChannel();
    for (int i = 0; i < 5; i++) {
      ManagedChannel channel = builder.build();
      gcpChannel.channelRefs.add(new ChannelRef(channel, i, i, MAX_STREAM));
    }
    assertEquals(5, gcpChannel.channelRefs.size());
    assertEquals(0, gcpChannel.getChannelRef().getAffinityCount());
    assertEquals(6, gcpChannel.channelRefs.size());

    // Add more channels, the smallest stream value is -1 with idx 6.
    int[] streams = new int[] {-1, 5, 7, 1};
    for (int i = 6; i < 10; i++) {
      ManagedChannel channel = builder.build();
      gcpChannel.channelRefs.add(new ChannelRef(channel, i, i, streams[i - 6]));
    }
    assertEquals(10, gcpChannel.channelRefs.size());
    assertEquals(6, gcpChannel.getChannelRef().getAffinityCount());
  }

  @Test
  public void testGetChannelRefMaxSize() throws Exception {
    resetGcpChannel();
    for (int i = 0; i < MAX_CHANNEL; i++) {
      ManagedChannel channel = builder.build();
      gcpChannel.channelRefs.add(new ChannelRef(channel, i, i, MAX_STREAM));
    }
    assertEquals(MAX_CHANNEL, gcpChannel.channelRefs.size());
    assertEquals(MAX_STREAM, gcpChannel.getChannelRef().getActiveStreamsCount());
    assertEquals(MAX_CHANNEL, gcpChannel.channelRefs.size());
  }

  @Test
  public void testBindUnbindKey() throws Exception {
    // Initialize the channel and bind the key, check the affinity count.
    ChannelRef cf1 = new ChannelRef(builder.build(), 1, 0, 5);
    ChannelRef cf2 = new ChannelRef(builder.build(), 1, 0, 4);
    gcpChannel.channelRefs.add(cf1);
    gcpChannel.channelRefs.add(cf2);
    gcpChannel.bind(1, "key1");
    gcpChannel.bind(2, "key2");
    gcpChannel.bind(1, "key1");
    assertEquals(2, gcpChannel.channelRefs.get(1).getAffinityCount());
    assertEquals(1, gcpChannel.channelRefs.get(2).getAffinityCount());
    assertEquals(2, gcpChannel.affinityKeyToChannelRef.size());

    // Try to use the channel with the affinity key.
    assertEquals(cf1, gcpChannel.getChannelRef("key1"));

    // Unbind the affinity key.
    gcpChannel.unbind("key1");
    assertEquals(2, gcpChannel.affinityKeyToChannelRef.size());
    gcpChannel.unbind("key1");
    gcpChannel.unbind("key2");
    assertEquals(0, gcpChannel.affinityKeyToChannelRef.size());
    assertEquals(0, gcpChannel.channelRefs.get(1).getAffinityCount());
    assertEquals(0, gcpChannel.channelRefs.get(2).getAffinityCount());

    // Finally, get the channelRef again.
    ChannelRef cf = gcpChannel.getChannelRef("key1");
    assertEquals(0, cf.getActiveStreamsCount());
  }
}
