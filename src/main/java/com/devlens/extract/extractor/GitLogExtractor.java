package com.devlens.extract.extractor;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Extracts recent commits from a local Git repository using JGit. */
public final class GitLogExtractor {

    private static final int MAX_COMMITS = 10;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    public List<Map<String, Object>> extract(Path repoRoot) throws IOException {
        try (Repository repo = new FileRepositoryBuilder()
                .findGitDir(repoRoot.toFile())
                .setMustExist(false)
                .build()) {
            if (repo.getDirectory() == null) return List.of();
            try (Git git = new Git(repo)) {
                Iterable<RevCommit> log = git.log().setMaxCount(MAX_COMMITS).call();
                List<Map<String, Object>> out = new ArrayList<>();
                for (RevCommit commit : log) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("hash", commit.getName());
                    e.put("author", commit.getAuthorIdent().getEmailAddress());
                    e.put("date", ISO.format(Instant.ofEpochSecond(commit.getCommitTime())
                            .atOffset(ZoneOffset.UTC)));
                    e.put("subject", commit.getShortMessage());
                    out.add(e);
                }
                return out;
            } catch (Exception ex) {
                throw new IOException("git log failed: " + ex.getMessage(), ex);
            }
        }
    }

    /** Returns the HEAD commit hash, or "unknown" if the repo has no commits. */
    public String headCommit(Path repoRoot) {
        try (Repository repo = new FileRepositoryBuilder()
                .findGitDir(repoRoot.toFile())
                .setMustExist(false)
                .build()) {
            if (repo.getDirectory() == null) return "unknown";
            var ref = repo.findRef("HEAD");
            if (ref == null) return "unknown";
            var objId = repo.resolve("HEAD");
            return objId != null ? objId.getName() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** Returns the symbolic HEAD branch name, or "unknown". */
    public String headBranch(Path repoRoot) {
        try (Repository repo = new FileRepositoryBuilder()
                .findGitDir(repoRoot.toFile())
                .setMustExist(false)
                .build()) {
            if (repo.getDirectory() == null) return "unknown";
            String branch = repo.getBranch();
            return branch != null ? branch : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}

