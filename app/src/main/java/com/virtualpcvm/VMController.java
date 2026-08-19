package com.virtualpcvm;

/** VM lifecycle controller. UI talks to this layer, never to Process. */
public final class VMController {
    public enum State { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

    public interface Listener {
        void onStateChanged(State state);
        void onLog(String line);
        void onError(Throwable error);
    }

    private final QemuRuntime runtime;
    private final QemuCommandBuilder commandBuilder = new QemuCommandBuilder();
    private final Object lock = new Object();
    private volatile State state = State.STOPPED;
    private volatile QemuProcess process;
    private volatile Throwable lastError;
    private long generation;

    public VMController(QemuRuntime runtime) { this.runtime = runtime; }

    public State getState() { return state; }
    public Throwable getLastError() { return lastError; }

    public void start(VMConfig config, Listener listener) {
        final long token;
        synchronized (lock) {
            if (state == State.STARTING || state == State.RUNNING) {
                throw new IllegalStateException("VM is already running");
            }
            token = ++generation;
            state = State.STARTING;
            lastError = null;
            notifyState(listener);
        }

        Thread worker = new Thread(() -> {
            QemuProcess qemu = null;
            try {
                if (config == null) throw new IllegalArgumentException("VM config is null");
                config.validateForStart();
                runtime.prepare();
                synchronized (lock) {
                    if (token != generation || state != State.STARTING) return;
                }
                java.util.List<String> command = commandBuilder.build(config, runtime);
                qemu = new QemuProcess(runtime);
                synchronized (lock) {
                    if (token != generation || state != State.STARTING) return;
                    process = qemu;
                }
                QemuProcess runningProcess = qemu;
                qemu.start(command, new QemuProcess.Listener() {
                    @Override public void onStdout(String line) { if (listener != null) listener.onLog(line); }
                    @Override public void onStderr(String line) { if (listener != null) listener.onLog("stderr: " + line); }
                    @Override public void onExit(int code) {
                        State newState;
                        synchronized (lock) {
                            if (process == runningProcess) process = null;
                            if (token == generation && state != State.STOPPING) {
                                state = code == 0 ? State.STOPPED : State.ERROR;
                                if (code != 0) lastError = new IllegalStateException("QEMU exited with code " + code);
                            } else if (token == generation && state == State.STOPPING) {
                                state = State.STOPPED;
                            }
                            newState = state;
                        }
                        if (listener != null) {
                            try { listener.onStateChanged(newState); } catch (Throwable ignored) { }
                        }
                    }
                });
                synchronized (lock) {
                    if (token != generation || state != State.STARTING) {
                        qemu.stop();
                        if (process == qemu) process = null;
                        return;
                    }
                    state = State.RUNNING;
                }
                notifyState(listener);
            } catch (Throwable error) {
                if (qemu != null && qemu.isRunning()) qemu.forceStop();
                synchronized (lock) {
                    if (token != generation) return;
                    state = State.ERROR;
                    lastError = error;
                    process = null;
                }
                if (listener != null) {
                    try { listener.onError(error); } catch (Throwable ignored) { }
                }
                notifyState(listener);
            }
        }, "vm-controller-start");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop(Listener listener) {
        final QemuProcess qemu;
        synchronized (lock) {
            ++generation;
            qemu = process;
            if (qemu == null) {
                state = State.STOPPED;
                notifyState(listener);
                return;
            }
            state = State.STOPPING;
        }
        notifyState(listener);

        Thread worker = new Thread(() -> {
            qemu.stop();
            synchronized (lock) {
                if (process == qemu) process = null;
                if (state == State.STOPPING) state = State.STOPPED;
            }
            notifyState(listener);
        }, "vm-controller-stop");
        worker.setDaemon(true);
        worker.start();
    }

    public void forceStop(Listener listener) {
        final QemuProcess qemu;
        synchronized (lock) {
            ++generation;
            qemu = process;
            state = State.STOPPING;
        }
        notifyState(listener);

        Thread worker = new Thread(() -> {
            if (qemu != null) qemu.forceStop();
            synchronized (lock) {
                if (process == qemu) process = null;
                if (state == State.STOPPING) state = State.STOPPED;
            }
            notifyState(listener);
        }, "vm-controller-force-stop");
        worker.setDaemon(true);
        worker.start();
    }

    public boolean isRunning() {
        QemuProcess qemu = process;
        return qemu != null && qemu.isRunning();
    }

    private void notifyState(Listener listener) {
        if (listener != null) {
            try { listener.onStateChanged(state); } catch (Throwable ignored) { }
        }
    }
}
