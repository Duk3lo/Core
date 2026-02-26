package org.astral.core.updates.github;

import java.util.LinkedHashMap;
import java.util.Map;

public class GithubConfig {
    public Map<String, RepoEntry> repos = new LinkedHashMap<>();

    public static class RepoEntry {
        public String link_repo;
        public String asset_type;
        public String downloadedHash;
        public String name_file_downloaded;

        public RepoEntry() {}

        @Override
        public String toString() {
            return "RepoEntry{" +
                    "link_repo='" + link_repo + '\'' +
                    ", asset_type='" + asset_type + '\'' +
                    ", downloadedHash='" + downloadedHash + '\'' +
                    ", name_file_downloaded='" + name_file_downloaded + '\'' +
                    '}';
        }
    }
}