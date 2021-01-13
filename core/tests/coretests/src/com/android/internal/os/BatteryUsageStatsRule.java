/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.os;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.SystemBatteryConsumer;
import android.os.UidBatteryConsumer;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

public class BatteryUsageStatsRule implements TestRule {
    private final PowerProfile mPowerProfile;
    private final MockClocks mMockClocks = new MockClocks();
    private final MockBatteryStatsImpl mBatteryStats = new MockBatteryStatsImpl(mMockClocks) {
        @Override
        public boolean hasBluetoothActivityReporting() {
            return true;
        }
    };

    private BatteryUsageStats mBatteryUsageStats;

    public BatteryUsageStatsRule() {
        Context context = InstrumentationRegistry.getContext();
        mPowerProfile = spy(new PowerProfile(context, true /* forTest */));
        mBatteryStats.setPowerProfile(mPowerProfile);
    }

    public BatteryUsageStatsRule setAveragePower(String key, double value) {
        when(mPowerProfile.getAveragePower(key)).thenReturn(value);
        return this;
    }

    public BatteryUsageStatsRule setAveragePower(String key, double[] values) {
        when(mPowerProfile.getNumElements(key)).thenReturn(values.length);
        for (int i = 0; i < values.length; i++) {
            when(mPowerProfile.getAveragePower(key, i)).thenReturn(values[i]);
        }
        return this;
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                noteOnBattery();
                base.evaluate();
            }
        };
    }

    private void noteOnBattery() {
        mBatteryStats.getOnBatteryTimeBase().setRunning(true, 0, 0);
    }

    public PowerProfile getPowerProfile() {
        return mPowerProfile;
    }

    public MockBatteryStatsImpl getBatteryStats() {
        return mBatteryStats;
    }

    public BatteryStatsImpl.Uid getUidStats(int uid) {
        return mBatteryStats.getUidStatsLocked(uid);
    }

    public void setTime(long realtimeUs, long uptimeUs) {
        mMockClocks.realtime = realtimeUs;
        mMockClocks.uptime = uptimeUs;
    }

    void apply(PowerCalculator calculator) {
        BatteryUsageStats.Builder builder = new BatteryUsageStats.Builder(0, 0, false);
        SparseArray<? extends BatteryStats.Uid> uidStats = mBatteryStats.getUidStats();
        for (int i = 0; i < uidStats.size(); i++) {
            builder.getOrCreateUidBatteryConsumerBuilder(uidStats.valueAt(i));
        }

        calculator.calculate(builder, mBatteryStats, mMockClocks.realtime, mMockClocks.uptime,
                BatteryUsageStatsQuery.DEFAULT, null);

        mBatteryUsageStats = builder.build();
    }

    public UidBatteryConsumer getUidBatteryConsumer(int uid) {
        for (UidBatteryConsumer ubc : mBatteryUsageStats.getUidBatteryConsumers()) {
            if (ubc.getUid() == uid) {
                return ubc;
            }
        }
        return null;
    }

    public SystemBatteryConsumer getSystemBatteryConsumer(
            @SystemBatteryConsumer.DrainType int drainType) {
        for (SystemBatteryConsumer sbc : mBatteryUsageStats.getSystemBatteryConsumers()) {
            if (sbc.getDrainType() == drainType) {
                return sbc;
            }
        }
        return null;
    }
}
