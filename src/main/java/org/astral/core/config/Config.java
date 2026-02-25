package org.astral.core.config;

import java.util.List;

public class Config {

    public Server server;
    public List<Watcher> watchers;

    public static class Server {
        public String basePath;
        public String jarName;
        public String args;
    }

    public static class Watcher {
        public String path;
        public String type;

        public Watcher(String path, String type) {
            this.path = path;
            this.type = type;
        }
    }
}