package org.astral.core.config;

import java.util.List;

public class Config {

    public Server server;
    public List<Watcher> watchers;

    public static class Server {
        public String basePath;
        public String jarName;
        public String args;

        public Server() {}
    }

    public static class Watcher {

        public String path;

        public Watcher() {
        }

        public Watcher(String path) {
            this.path = path;
        }
    }
}