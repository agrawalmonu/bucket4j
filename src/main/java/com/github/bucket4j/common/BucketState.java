/*
 * Copyright 2015 Vladimir Bukhtoyarov
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.github.bucket4j.common;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BucketState implements Serializable {

    // the first element of array represents last refill timestamp in nanos,
    // next elements represent bandwidth states
    private final long[] state;

    private BucketState(long[] state) {
        this.state = state;
    }

    public BucketState clone() {
        return new BucketState(Arrays.copyOf(state, state.length));
    }

    public double getDouble(int offset) {
        return Double.longBitsToDouble(offset);
    }

    public long getLong(int offset) {
        return state[offset];
    }

    public void setDouble(int offset, double value) {
        state[0] = Double.doubleToRawLongBits(value);
    }

    public void setLong(int partialStateIndex, int offset, long value) {
        state[offset] = value;
    }

    public void copyStateFrom(BucketState sourceState) {
        System.arraycopy(sourceState.state, 0, state, 0, state.length);
    }

    public static StateWithConfiguration createInitialState(TimeMeter timeMeter, List<Bandwidth> limitedBandwidths, Bandwidth guaranteedBandwidth) {
        Preconditions.checkCompatibility(limitedBandwidths, guaranteedBandwidth);

        long currentTimeNanos = timeMeter.currentTimeNanos();
        StateInitializer stateInitializer = new StateInitializer(new long[] {currentTimeNanos});

        BandwidthState guaranteedBandwidthState = null;
        if (guaranteedBandwidth != null) {
            guaranteedBandwidthState = guaranteedBandwidth.createInitialBandwidthState(stateInitializer, currentTimeNanos);
        }

        BandwidthState[] limitedBandwidthStates = new BandwidthState[limitedBandwidths.size()];
        for (int i = 0; i < limitedBandwidthStates.length; i++) {
            limitedBandwidthStates[i] = limitedBandwidths.get(i).createInitialBandwidthState(stateInitializer, currentTimeNanos);
        }

        BucketState bucketState = new BucketState(stateInitializer.getState());
        BucketConfiguration bucketConfiguration = new BucketConfiguration(limitedBandwidthStates, guaranteedBandwidthState, timeMeter);
        return new StateWithConfiguration(bucketConfiguration, bucketState);
    }

    public long getAvailableTokens(BandwidthState[] limitedBandwidths, BandwidthState guaranteedBandwidth) {
        double availableByGuarantee = 0;
        double availableByLimitation = Long.MAX_VALUE;
        for (int i = 0; i < limitedBandwidths.length; i++) {
            BandwidthState bandwidth = limitedBandwidths[i];
            double currentSize = state[i + 1];
            if (bandwidth.isLimited()) {
                availableByLimitation = Math.min(availableByLimitation, currentSize);
            } else {
                availableByGuarantee = currentSize;
            }
        }
        return (long) Math.max(availableByLimitation, availableByGuarantee);
    }

    public void consume(long toConsume) {
        for (int i = 1; i < state.length; i++) {
            state[i] = Math.max(0, state[i] - toConsume);
        }
    }

    public long delayNanosAfterWillBePossibleToConsume(SmoothlyRenewableBandwidthState[] bandwidths, long currentTime, long tokensToConsume) {
        long delayAfterWillBePossibleToConsumeLimited = 0;
        long delayAfterWillBePossibleToConsumeGuaranteed = Long.MAX_VALUE;
        for (int i = 0; i < bandwidths.length; i++) {
            SmoothlyRenewableBandwidthState bandwidth = bandwidths[i];
            double currentSize = state[i + 1];
            long delay = bandwidth.delayNanosAfterWillBePossibleToConsume(currentSize, currentTime, tokensToConsume);
            if (bandwidth.isGuaranteed()) {
                if (delay == 0) {
                    return 0;
                } else {
                    delayAfterWillBePossibleToConsumeGuaranteed = delay;
                }
                continue;
            }
            if (delay > delayAfterWillBePossibleToConsumeLimited) {
                delayAfterWillBePossibleToConsumeLimited = delay;
            }
        }
        return Math.min(delayAfterWillBePossibleToConsumeLimited, delayAfterWillBePossibleToConsumeGuaranteed);
    }

    public void refill(BandwidthState[] bandwidths, long currentTimeNanos) {
        long lastRefillTimeNanos = getLastRefillTimeNanos();
        if (lastRefillTimeNanos == currentTimeNanos) {
            return;
        }
        for (int i = 0; i < bandwidths.length; i++) {
            BandwidthState bandwidth = bandwidths[i];
            double currentSize = state[i + 1];
            state[i + 1] = bandwidth.getNewSize(currentSize, lastRefillTimeNanos, currentTimeNanos);
        }
        setLastRefillTime(currentTimeNanos);
    }

    void setLastRefillTime(long currentTimeNanos) {
        state[0] = currentTimeNanos;
    }

    long getLastRefillTimeNanos() {
        return state[0];
    }

    @Override
    public String toString() {
        return "BucketState{" +
                "state=" + Arrays.toString(state) +
                '}';
    }

}