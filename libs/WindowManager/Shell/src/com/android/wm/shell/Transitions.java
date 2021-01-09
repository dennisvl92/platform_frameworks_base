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

package com.android.wm.shell;

import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.window.TransitionInfo.FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.ITransitionPlayer;
import android.window.TransitionInfo;
import android.window.WindowContainerTransaction;
import android.window.WindowOrganizer;

import androidx.annotation.BinderThread;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.util.ArrayList;

/** Plays transition animations */
public class Transitions {
    private static final String TAG = "ShellTransitions";

    /** Set to {@code true} to enable shell transitions. */
    public static final boolean ENABLE_SHELL_TRANSITIONS =
            SystemProperties.getBoolean("persist.debug.shell_transit", false);

    private final WindowOrganizer mOrganizer;
    private final TransactionPool mTransactionPool;
    private final ShellExecutor mMainExecutor;
    private final ShellExecutor mAnimExecutor;
    private final TransitionPlayerImpl mPlayerImpl;

    /** List of possible handlers. Ordered by specificity (eg. tapped back to front). */
    private final ArrayList<TransitionHandler> mHandlers = new ArrayList<>();

    private static final class ActiveTransition {
        ArrayList<Animator> mAnimations = null;
        TransitionHandler mFirstHandler = null;
    }

    /** Keeps track of currently tracked transitions and all the animations associated with each */
    private final ArrayMap<IBinder, ActiveTransition> mActiveTransitions = new ArrayMap<>();

    public Transitions(@NonNull WindowOrganizer organizer, @NonNull TransactionPool pool,
            @NonNull ShellExecutor mainExecutor, @NonNull ShellExecutor animExecutor) {
        mOrganizer = organizer;
        mTransactionPool = pool;
        mMainExecutor = mainExecutor;
        mAnimExecutor = animExecutor;
        mPlayerImpl = new TransitionPlayerImpl();
    }

    public void register(ShellTaskOrganizer taskOrganizer) {
        taskOrganizer.registerTransitionPlayer(mPlayerImpl);
    }

    /**
     * Adds a handler candidate.
     * @see TransitionHandler
     */
    public void addHandler(@NonNull TransitionHandler handler) {
        mHandlers.add(handler);
    }

    public ShellExecutor getMainExecutor() {
        return mMainExecutor;
    }

    public ShellExecutor getAnimExecutor() {
        return mAnimExecutor;
    }

    // TODO(shell-transitions): real animations
    private void startExampleAnimation(@NonNull IBinder transition, @NonNull SurfaceControl leash,
            boolean show) {
        final float end = show ? 1.f : 0.f;
        final float start = 1.f - end;
        final SurfaceControl.Transaction transaction = mTransactionPool.acquire();
        final ValueAnimator va = ValueAnimator.ofFloat(start, end);
        va.setDuration(500);
        va.addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();
            transaction.setAlpha(leash, start * (1.f - fraction) + end * fraction);
            transaction.apply();
        });
        final Runnable finisher = () -> {
            transaction.setAlpha(leash, end);
            transaction.apply();
            mTransactionPool.release(transaction);
            mMainExecutor.execute(() -> {
                mActiveTransitions.get(transition).mAnimations.remove(va);
                onFinish(transition);
            });
        };
        va.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) { }

            @Override
            public void onAnimationEnd(Animator animation) {
                finisher.run();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                finisher.run();
            }

            @Override
            public void onAnimationRepeat(Animator animation) { }
        });
        mActiveTransitions.get(transition).mAnimations.add(va);
        mAnimExecutor.execute(va::start);
    }

    /** @return true if the transition was triggered by opening something vs closing something */
    public static boolean isOpeningType(@WindowManager.TransitionType int type) {
        return type == TRANSIT_OPEN
                || type == TRANSIT_TO_FRONT
                || type == WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
    }

    /**
     * Reparents all participants into a shared parent and orders them based on: the global transit
     * type, their transit mode, and their destination z-order.
     */
    private static void setupStartState(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t) {
        boolean isOpening = isOpeningType(info.getType());
        if (info.getRootLeash().isValid()) {
            t.show(info.getRootLeash());
        }
        // changes should be ordered top-to-bottom in z
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            final SurfaceControl leash = change.getLeash();
            final int mode = info.getChanges().get(i).getMode();

            // Don't move anything with an animating parent
            if (change.getParent() != null) {
                if (mode == TRANSIT_OPEN || mode == TRANSIT_TO_FRONT || mode == TRANSIT_CHANGE) {
                    t.show(leash);
                    t.setMatrix(leash, 1, 0, 0, 1);
                    t.setAlpha(leash, 1.f);
                    t.setPosition(leash, change.getEndRelOffset().x, change.getEndRelOffset().y);
                }
                continue;
            }

            t.reparent(leash, info.getRootLeash());
            t.setPosition(leash, change.getStartAbsBounds().left - info.getRootOffset().x,
                    change.getStartAbsBounds().top - info.getRootOffset().y);
            // Put all the OPEN/SHOW on top
            if (mode == TRANSIT_OPEN || mode == TRANSIT_TO_FRONT) {
                t.show(leash);
                t.setMatrix(leash, 1, 0, 0, 1);
                if (isOpening) {
                    // put on top with 0 alpha
                    t.setLayer(leash, info.getChanges().size() - i);
                    if ((change.getFlags() & FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT) != 0) {
                        // This received a transferred starting window, so make it immediately
                        // visible.
                        t.setAlpha(leash, 1.f);
                    } else {
                        t.setAlpha(leash, 0.f);
                    }
                } else {
                    // put on bottom and leave it visible
                    t.setLayer(leash, -i);
                    t.setAlpha(leash, 1.f);
                }
            } else if (mode == TRANSIT_CLOSE || mode == TRANSIT_TO_BACK) {
                if (isOpening) {
                    // put on bottom and leave visible
                    t.setLayer(leash, -i);
                } else {
                    // put on top
                    t.setLayer(leash, info.getChanges().size() - i);
                }
            } else { // CHANGE
                t.setLayer(leash, info.getChanges().size() - i);
            }
        }
    }

    private void onTransitionReady(@NonNull IBinder transitionToken, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "onTransitionReady %s: %s",
                transitionToken, info);
        final ActiveTransition active = mActiveTransitions.get(transitionToken);
        if (active == null) {
            throw new IllegalStateException("Got transitionReady for non-active transition "
                    + transitionToken + ". expecting one of " + mActiveTransitions.keySet());
        }
        if (active.mAnimations != null) {
            throw new IllegalStateException("Got a duplicate onTransitionReady call for "
                    + transitionToken);
        }
        if (!info.getRootLeash().isValid()) {
            // Invalid root-leash implies that the transition is empty/no-op, so just do
            // housekeeping and return.
            t.apply();
            onFinish(transitionToken);
            return;
        }

        setupStartState(info, t);

        final Runnable finishRunnable = () -> onFinish(transitionToken);
        // If a handler chose to uniquely run this animation, try delegating to it.
        if (active.mFirstHandler != null && active.mFirstHandler.startAnimation(
                transitionToken, info, t, finishRunnable)) {
            return;
        }
        // Otherwise give every other handler a chance (in order)
        for (int i = mHandlers.size() - 1; i >= 0; --i) {
            if (mHandlers.get(i) == active.mFirstHandler) continue;
            if (mHandlers.get(i).startAnimation(transitionToken, info, t, finishRunnable)) {
                return;
            }
        }

        // No handler chose to perform this animation, so fall-back to the
        // default animation handling.
        final boolean isOpening = isOpeningType(info.getType());
        active.mAnimations = new ArrayList<>(); // Play fade animations
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);

            // Don't animate anything with an animating parent
            if (change.getParent() != null) continue;

            final int mode = info.getChanges().get(i).getMode();
            if (isOpening && (mode == TRANSIT_OPEN || mode == TRANSIT_TO_FRONT)) {
                if ((change.getFlags() & FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT) != 0) {
                    // This received a transferred starting window, so don't animate
                    continue;
                }
                // fade in
                startExampleAnimation(transitionToken, change.getLeash(), true /* show */);
            } else if (!isOpening && (mode == TRANSIT_CLOSE || mode == TRANSIT_TO_BACK)) {
                // fade out
                startExampleAnimation(transitionToken, change.getLeash(), false /* show */);
            }
        }
        t.apply();
        onFinish(transitionToken);
    }

    private void onFinish(IBinder transition) {
        final ActiveTransition active = mActiveTransitions.get(transition);
        if (active.mAnimations != null && !active.mAnimations.isEmpty()) return;
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                "Transition animations finished, notifying core %s", transition);
        mActiveTransitions.remove(transition);
        mOrganizer.finishTransition(transition, null, null);
    }

    private void requestStartTransition(int type, @NonNull IBinder transitionToken,
            @Nullable ActivityManager.RunningTaskInfo triggerTask) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "Transition requested: type=%d %s",
                type, transitionToken);

        if (mActiveTransitions.containsKey(transitionToken)) {
            throw new RuntimeException("Transition already started " + transitionToken);
        }
        final ActiveTransition active = new ActiveTransition();
        WindowContainerTransaction wct = null;
        for (int i = mHandlers.size() - 1; i >= 0; --i) {
            wct = mHandlers.get(i).handleRequest(type, transitionToken, triggerTask);
            if (wct != null) {
                active.mFirstHandler = mHandlers.get(i);
                break;
            }
        }
        IBinder transition = mOrganizer.startTransition(type, transitionToken, wct);
        mActiveTransitions.put(transition, active);
    }

    /** Start a new transition directly. */
    public IBinder startTransition(@WindowManager.TransitionType int type,
            @NonNull WindowContainerTransaction wct, @Nullable TransitionHandler handler) {
        final ActiveTransition active = new ActiveTransition();
        active.mFirstHandler = handler;
        IBinder transition = mOrganizer.startTransition(type, null /* token */, wct);
        mActiveTransitions.put(transition, active);
        return transition;
    }

    /**
     * Interface for something which can handle a subset of transitions.
     */
    public interface TransitionHandler {
        /**
         * Starts a transition animation. This is always called if handleRequest returned non-null
         * for a particular transition. Otherwise, it is only called if no other handler before
         * it handled the transition.
         *
         * @return true if transition was handled, false if not (falls-back to default).
         */
        boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction t, @NonNull Runnable finishCallback);

        /**
         * Potentially handles a startTransition request.
         * @param type The transition type
         * @param triggerTask The task which triggered this transition request.
         * @return WCT to apply with transition-start or null if this handler isn't handling
         *         the request.
         */
        @Nullable
        WindowContainerTransaction handleRequest(@WindowManager.TransitionType int type,
                @NonNull IBinder transition,
                @Nullable ActivityManager.RunningTaskInfo triggerTask);
    }

    @BinderThread
    private class TransitionPlayerImpl extends ITransitionPlayer.Stub {
        @Override
        public void onTransitionReady(IBinder iBinder, TransitionInfo transitionInfo,
                SurfaceControl.Transaction transaction) throws RemoteException {
            mMainExecutor.execute(() -> {
                Transitions.this.onTransitionReady(iBinder, transitionInfo, transaction);
            });
        }

        @Override
        public void requestStartTransition(int i, IBinder iBinder,
                ActivityManager.RunningTaskInfo runningTaskInfo) throws RemoteException {
            mMainExecutor.execute(() -> {
                Transitions.this.requestStartTransition(i, iBinder, runningTaskInfo);
            });
        }
    }
}
