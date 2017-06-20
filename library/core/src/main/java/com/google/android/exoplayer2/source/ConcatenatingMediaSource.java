/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.source;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.upstream.Allocator;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Util;
import java.io.IOException;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Concatenates multiple {@link MediaSource}s. It is valid for the same {@link MediaSource} instance
 * to be present more than once in the concatenation.
 */
public final class ConcatenatingMediaSource implements MediaSource {

  private final MediaSource[] mediaSources;
  private final Timeline[] timelines;
  private final Object[] manifests;
  private final Map<MediaPeriod, Integer> sourceIndexByMediaPeriod;
  private final boolean[] duplicateFlags;
  private final boolean isRepeatOneAtomic;

  private Listener listener;
  private ConcatenatedTimeline timeline;

  /**
   * @param mediaSources The {@link MediaSource}s to concatenate. It is valid for the same
   *     {@link MediaSource} instance to be present more than once in the array.
   */
  public ConcatenatingMediaSource(MediaSource... mediaSources) {
    this(false, mediaSources);
  }

  /**
   * @param isRepeatOneAtomic Whether the concatenated media source shall be treated as atomic
   *     (i.e., repeated in its entirety) when repeat mode is set to
   *     {@code ExoPlayer.REPEAT_MODE_ONE}.
   * @param mediaSources The {@link MediaSource}s to concatenate. It is valid for the same
   *     {@link MediaSource} instance to be present more than once in the array.
   */
  public ConcatenatingMediaSource(boolean isRepeatOneAtomic, MediaSource... mediaSources) {
    this.mediaSources = mediaSources;
    this.isRepeatOneAtomic = isRepeatOneAtomic;
    timelines = new Timeline[mediaSources.length];
    manifests = new Object[mediaSources.length];
    sourceIndexByMediaPeriod = new HashMap<>();
    duplicateFlags = buildDuplicateFlags(mediaSources);
  }

  @Override
  public void prepareSource(ExoPlayer player, boolean isTopLevelSource, Listener listener) {
    this.listener = listener;
    for (int i = 0; i < mediaSources.length; i++) {
      if (!duplicateFlags[i]) {
        final int index = i;
        mediaSources[i].prepareSource(player, false, new Listener() {
          @Override
          public void onSourceInfoRefreshed(Timeline timeline, Object manifest) {
            handleSourceInfoRefreshed(index, timeline, manifest);
          }
        });
      }
    }
  }

  @Override
  public void maybeThrowSourceInfoRefreshError() throws IOException {
    for (int i = 0; i < mediaSources.length; i++) {
      if (!duplicateFlags[i]) {
        mediaSources[i].maybeThrowSourceInfoRefreshError();
      }
    }
  }

  @Override
  public MediaPeriod createPeriod(int index, Allocator allocator) {
    int sourceIndex = timeline.getChildIndexByPeriodIndex(index);
    int periodIndexInSource = index - timeline.getFirstPeriodIndexInChild(sourceIndex);
    MediaPeriod mediaPeriod =
        mediaSources[sourceIndex].createPeriod(periodIndexInSource, allocator);
    sourceIndexByMediaPeriod.put(mediaPeriod, sourceIndex);
    return mediaPeriod;
  }

  @Override
  public void releasePeriod(MediaPeriod mediaPeriod) {
    int sourceIndex = sourceIndexByMediaPeriod.get(mediaPeriod);
    sourceIndexByMediaPeriod.remove(mediaPeriod);
    mediaSources[sourceIndex].releasePeriod(mediaPeriod);
  }

  @Override
  public void releaseSource() {
    for (int i = 0; i < mediaSources.length; i++) {
      if (!duplicateFlags[i]) {
        mediaSources[i].releaseSource();
      }
    }
  }

  private void handleSourceInfoRefreshed(int sourceFirstIndex, Timeline sourceTimeline,
      Object sourceManifest) {
    // Set the timeline and manifest.
    timelines[sourceFirstIndex] = sourceTimeline;
    manifests[sourceFirstIndex] = sourceManifest;
    // Also set the timeline and manifest for any duplicate entries of the same source.
    for (int i = sourceFirstIndex + 1; i < mediaSources.length; i++) {
      if (mediaSources[i] == mediaSources[sourceFirstIndex]) {
        timelines[i] = sourceTimeline;
        manifests[i] = sourceManifest;
      }
    }
    for (Timeline timeline : timelines) {
      if (timeline == null) {
        // Don't invoke the listener until all sources have timelines.
        return;
      }
    }
    timeline = new ConcatenatedTimeline(timelines.clone(), isRepeatOneAtomic);
    listener.onSourceInfoRefreshed(timeline, manifests.clone());
  }

  private static boolean[] buildDuplicateFlags(MediaSource[] mediaSources) {
    boolean[] duplicateFlags = new boolean[mediaSources.length];
    IdentityHashMap<MediaSource, Void> sources = new IdentityHashMap<>(mediaSources.length);
    for (int i = 0; i < mediaSources.length; i++) {
      MediaSource source = mediaSources[i];
      if (!sources.containsKey(source)) {
        sources.put(source, null);
      } else {
        duplicateFlags[i] = true;
      }
    }
    return duplicateFlags;
  }

  /**
   * A {@link Timeline} that is the concatenation of one or more {@link Timeline}s.
   */
  private static final class ConcatenatedTimeline extends AbstractConcatenatedTimeline {

    private final Timeline[] timelines;
    private final int[] sourcePeriodOffsets;
    private final int[] sourceWindowOffsets;
    private final boolean isRepeatOneAtomic;

    public ConcatenatedTimeline(Timeline[] timelines, boolean isRepeatOneAtomic) {
      int[] sourcePeriodOffsets = new int[timelines.length];
      int[] sourceWindowOffsets = new int[timelines.length];
      long periodCount = 0;
      int windowCount = 0;
      for (int i = 0; i < timelines.length; i++) {
        Timeline timeline = timelines[i];
        periodCount += timeline.getPeriodCount();
        Assertions.checkState(periodCount <= Integer.MAX_VALUE,
            "ConcatenatingMediaSource children contain too many periods");
        sourcePeriodOffsets[i] = (int) periodCount;
        windowCount += timeline.getWindowCount();
        sourceWindowOffsets[i] = windowCount;
      }
      this.timelines = timelines;
      this.sourcePeriodOffsets = sourcePeriodOffsets;
      this.sourceWindowOffsets = sourceWindowOffsets;
      this.isRepeatOneAtomic = isRepeatOneAtomic;
    }

    @Override
    public int getWindowCount() {
      return sourceWindowOffsets[sourceWindowOffsets.length - 1];
    }

    @Override
    public int getPeriodCount() {
      return sourcePeriodOffsets[sourcePeriodOffsets.length - 1];
    }

    @Override
    public int getNextWindowIndex(int windowIndex, @ExoPlayer.RepeatMode int repeatMode) {
      if (isRepeatOneAtomic && repeatMode == ExoPlayer.REPEAT_MODE_ONE) {
        repeatMode = ExoPlayer.REPEAT_MODE_ALL;
      }
      return super.getNextWindowIndex(windowIndex, repeatMode);
    }

    @Override
    public int getPreviousWindowIndex(int windowIndex, @ExoPlayer.RepeatMode int repeatMode) {
      if (isRepeatOneAtomic && repeatMode == ExoPlayer.REPEAT_MODE_ONE) {
        repeatMode = ExoPlayer.REPEAT_MODE_ALL;
      }
      return super.getPreviousWindowIndex(windowIndex, repeatMode);
    }

    @Override
    protected void getChildDataByPeriodIndex(int periodIndex, ChildDataHolder childData) {
      int childIndex = getChildIndexByPeriodIndex(periodIndex);
      getChildDataByChildIndex(childIndex, childData);
    }

    @Override
    protected void getChildDataByWindowIndex(int windowIndex, ChildDataHolder childData) {
      int childIndex = Util.binarySearchFloor(sourceWindowOffsets, windowIndex, true, false) + 1;
      getChildDataByChildIndex(childIndex, childData);
    }

    @Override
    protected boolean getChildDataByChildUid(Object childUid, ChildDataHolder childData) {
      if (!(childUid instanceof Integer)) {
        return false;
      }
      int childIndex = (Integer) childUid;
      getChildDataByChildIndex(childIndex, childData);
      return true;
    }

    private void getChildDataByChildIndex(int childIndex, ChildDataHolder childData) {
      childData.timeline = timelines[childIndex];
      childData.firstPeriodIndexInChild = getFirstPeriodIndexInChild(childIndex);
      childData.firstWindowIndexInChild = getFirstWindowIndexInChild(childIndex);
      childData.uid = childIndex;
    }

    private int getChildIndexByPeriodIndex(int periodIndex) {
      return Util.binarySearchFloor(sourcePeriodOffsets, periodIndex, true, false) + 1;
    }

    private int getFirstPeriodIndexInChild(int childIndex) {
      return childIndex == 0 ? 0 : sourcePeriodOffsets[childIndex - 1];
    }

    private int getFirstWindowIndexInChild(int childIndex) {
      return childIndex == 0 ? 0 : sourceWindowOffsets[childIndex - 1];
    }

  }

}