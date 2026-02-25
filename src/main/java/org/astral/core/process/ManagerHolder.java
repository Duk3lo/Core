package org.astral.core.process;

public class ManagerHolder {

    private final JarProcessManager manager;

    public ManagerHolder(JarProcessManager manager) {
        this.manager = manager;
    }

    public synchronized JarProcessManager get() {
        return manager;
    }

}