package org.astral.core.process;

public class ManagerHolder {

    private volatile JarProcessManager manager;

    public ManagerHolder(JarProcessManager manager) {
        this.manager = manager;
    }

    public synchronized JarProcessManager get() {
        return manager;
    }

    public synchronized void set(JarProcessManager manager) {
        this.manager = manager;
    }
}