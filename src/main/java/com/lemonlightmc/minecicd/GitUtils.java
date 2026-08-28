package com.lemonlightmc.minecicd;

import org.apache.commons.io.FileUtils;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.InvalidConfigurationException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.ContentMergeStrategy;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;

import static com.lemonlightmc.minecicd.Messages.getCleanMessage;
import static com.lemonlightmc.minecicd.MineCICD.busyLock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;

public abstract class GitUtils {
    public static CredentialsProvider getCredentials() {
        final String user = Config.getString("git.user");
        if (user.isEmpty()) {
            throw new IllegalStateException("Git user is not set");
        }

        final String pass = Config.getString("git.pass");
        if (pass.isEmpty()) {
            throw new IllegalStateException("Git password is not set");
        }
        return new UsernamePasswordCredentialsProvider(user, pass);
    }

    public static void loadGitIgnore() {
        final File gitIgnoreFile = new File(new File("."), ".gitignore");
        if (gitIgnoreFile.exists())
            return;

        final InputStreamReader reader = new InputStreamReader(
                Objects.requireNonNull(MineCICD.plugin.getResource(".gitignore")), StandardCharsets.UTF_8);

        final Scanner scanner = new Scanner(reader);
        try {
            Files.write(gitIgnoreFile.toPath(), scanner.useDelimiter("\\A").next().getBytes());
        } catch (final IOException e) {
            MineCICD.log("Failed to write .gitignore", Level.SEVERE);
            MineCICD.logError(e);
        }
    }

    public static int getIndex(final List<String> list, final String value) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).equals(value)) {
                return i;
            }
        }
        return 0;
    }

    public static void allowInGitIgnore(final String path, final boolean isDirectory) throws IOException {
        String gitString = path.replace("\\", "/");
        if (isDirectory) {
            gitString = gitString.replaceAll("/\\*$", "") + "/*";
        }

        final File gitIgnoreFile = new File(new File("."), ".gitignore");
        if (!gitIgnoreFile.exists()) {
            throw new IllegalStateException(".gitignore does not exist");
        }

        final List<String> lines = Files.readAllLines(gitIgnoreFile.toPath());

        int endIndex = getIndex(lines, "# MineCICD GITIGNORE PART END MARKER");
        final int startIndex = getIndex(lines, "# MineCICD GITIGNORE PART BEGIN MARKER");
        if (endIndex == 0 || startIndex == 0) {
            throw new IllegalStateException("MineCICD PART markers not found in .gitignore");
        }

        // remove whatever excludes within the path
        String trimStart = gitString;
        if (isDirectory) {
            trimStart = trimStart.substring(0, trimStart.length() - 2);
        }
        for (int i = startIndex; i < endIndex + 1; i++) {
            if (lines.get(i).startsWith("!/" + trimStart) || lines.get(i).startsWith("/" + trimStart)) {
                lines.remove(i);
                i--;
                endIndex--;
            }
        }

        // allow this exact path
        final String inclusionRule = "!/" + gitString + "*";
        lines.add(startIndex + 1, inclusionRule);

        fixAndSaveGitIgnore(lines, gitIgnoreFile);
    }

    public static void removeFromGitIgnore(final String path, final boolean isDirectory) throws IOException {
        String gitString = path.replace("\\", "/");
        if (isDirectory) {
            gitString = gitString.replaceAll("/\\*$", "") + "/*";
        }

        final File gitIgnoreFile = new File(new File("."), ".gitignore");
        if (!gitIgnoreFile.exists()) {
            throw new IllegalStateException(".gitignore does not exist");
        }

        final List<String> lines = Files.readAllLines(gitIgnoreFile.toPath());

        int endIndex = getIndex(lines, "# MineCICD GITIGNORE PART END MARKER");
        final int startIndex = getIndex(lines, "# MineCICD GITIGNORE PART BEGIN MARKER");
        if (endIndex == 0 || startIndex == 0) {
            throw new IllegalStateException("MineCICD PART markers not found in .gitignore");
        }

        // remove whatever else includes within this path
        String trimStart = gitString;
        if (isDirectory) {
            trimStart = trimStart.substring(0, trimStart.length() - 2);
        }
        for (int i = startIndex; i < endIndex; i++) {
            if (lines.get(i).startsWith("!/" + trimStart) || lines.get(i).startsWith("/" + trimStart)) {
                lines.remove(i);
                i--;
                endIndex--;
            }
        }

        lines.add(endIndex, "/" + gitString);
        if (isDirectory) {
            lines.add(endIndex, "!/" + gitString + "/");
        }

        fixAndSaveGitIgnore(lines, gitIgnoreFile);
    }

    public static void fixAndSaveGitIgnore(final List<String> fileLines, final File gitIgnoreFile) throws IOException {
        // remove duplicates
        final Set<String> set = new HashSet<>();
        final ArrayList<String> newLines = new ArrayList<>();
        for (final String line : fileLines) {
            if (line.isEmpty() || set.add(line)) {
                newLines.add(line);
            }
        }

        final int endIndex = getIndex(newLines, "# MineCICD GITIGNORE PART END MARKER");
        final int startIndex = getIndex(newLines, "# MineCICD GITIGNORE PART BEGIN MARKER");

        // sort the sublist between markers
        final List<String> subListParted = newLines.subList(startIndex + 1, endIndex);
        subListParted.sort((o1, o2) -> {
            // sort, so that "a" is before "!a", but "b" is after "!a"
            final boolean o1Ex = o1.startsWith("!");
            final boolean o2Ex = o2.startsWith("!");

            String o1Sub = o1Ex ? o1.substring(1) : o1;
            String o2Sub = o2Ex ? o2.substring(1) : o2;

            // remove trailing *
            if (o1Sub.endsWith("*")) {
                o1Sub = o1Sub.substring(0, o1Sub.length() - 1);
            }
            if (o2Sub.endsWith("*")) {
                o2Sub = o2Sub.substring(0, o2Sub.length() - 1);
            }

            final int compare = o1Sub.compareTo(o2Sub);
            if (compare == 0) {
                return Boolean.compare(o1Ex, o2Ex);
            }
            return compare;
        });

        Files.write(gitIgnoreFile.toPath(), newLines);
    }

    public static boolean activeRepoExists() {
        final File repoFolder = new File(".");
        return repoFolder.exists() && new File(repoFolder, ".git").exists()
                && new File(repoFolder, ".gitignore").exists();
    }

    public static String getCurrentRevision() {
        if (!activeRepoExists()) {
            return "";
        }

        try (Git git = Git.open(new File("."))) {
            return git.log().setMaxCount(1).call().iterator().next().getName();
        } catch (final NoHeadException ignored) {
            return "";
        } catch (final Exception e) {
            MineCICD.log("Failed to get current revision", Level.SEVERE);
            MineCICD.logError(e);
            return "";
        }
    }

    public static String getLatestRemoteRevision() {
        if (!activeRepoExists()) {
            return "";
        }

        try (Git git = Git.open(new File("."))) {
            git.fetch().setCredentialsProvider(getCredentials()).call();
            return git.log().setMaxCount(1).add(git.getRepository().resolve("origin/" + Config.getString("git.branch")))
                    .call().iterator().next().getName();
        } catch (final Exception e) {
            MineCICD.log("Failed to get latest remote revision", Level.SEVERE);
            MineCICD.logError(e);
            return "";
        }
    }

    public static Set<String> getLocalChanges() {
        if (!activeRepoExists()) {
            return new HashSet<>();
        }

        try (Git git = Git.open(new File("."))) {
            git.add().addFilepattern(".").call();
            return git.status().call().getUncommittedChanges();
        } catch (final Exception e) {
            MineCICD.log("Failed to check for changes", Level.SEVERE);
            MineCICD.logError(e);
            throw new IllegalStateException("Failed to check for changes");
        }
    }

    public static List<DiffEntry> getRemoteChanges(final Git git) throws GitAPIException, IOException {
        final boolean ownsBusy = !busyLock;
        if (ownsBusy)
            busyLock = true;
        try {
            git.fetch().setCredentialsProvider(getCredentials()).call();

            final ObjectId oldRevId = git.getRepository().resolve("HEAD");
            final ObjectId newRevId = git.getRepository().resolve("origin/" + Config.getString("git.branch"));
            return getChangesBetween(git, oldRevId, newRevId);
        } finally {
            if (ownsBusy)
                busyLock = false;
        }
    }

    public static List<DiffEntry> getChangesBetween(final Git git, final ObjectId oldRevId, final ObjectId newRevId)
            throws IOException {
        final AbstractTreeIterator oldTreeParser = prepareTreeParser(git.getRepository(), oldRevId);
        final AbstractTreeIterator newTreeParser = prepareTreeParser(git.getRepository(), newRevId);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final DiffFormatter diffFormatter = new DiffFormatter(out);
        diffFormatter.setRepository(git.getRepository());

        diffFormatter.close();
        return diffFormatter.scan(oldTreeParser, newTreeParser);
    }

    public static boolean pull() throws GitAPIException, URISyntaxException, IOException, InvalidConfigurationException,
            InterruptedException {
        final boolean ownsBusy = !busyLock;
        if (ownsBusy)
            busyLock = true;

        final String bar = MineCICD.addBar(getCleanMessage("bossbar-pulling", true), BarColor.BLUE, BarStyle.SOLID);

        try {
            final String repo = Config.getString("git.repo");
            if (repo.isEmpty()) {
                throw new IllegalStateException("Git repository is not set");
            }

            final String branch = Config.getString("git.branch");
            if (branch.isEmpty()) {
                throw new IllegalStateException("Git branch is not set");
            }

            boolean changes;
            final String oldCommit = getCurrentRevision();
            if (!activeRepoExists()) {
                try (Git git = Git.init().setDirectory(new File(".")).call()) {
                    GitSecret.configureGitSecretFiltering(GitSecret.readFromSecretsStore());
                    git.remoteAdd().setName("origin").setUri(new URIish(repo)).call();
                    git.fetch().setCredentialsProvider(getCredentials()).call();

                    boolean newRepo = true;
                    if (git.branchList().call().stream()
                            .anyMatch(ref -> ref.getName().equals("refs/remotes/origin/" + branch))) {
                        git.reset().setMode(ResetCommand.ResetType.HARD).setRef("origin/" + branch).call();
                        git.checkout().setName(branch).call();
                        newRepo = false;
                    }

                    git.add().addFilepattern(".gitignore").call();
                    if (!getLocalChanges().isEmpty() || newRepo) {
                        git.commit().setAuthor("MineCICD", "MineCICD").setMessage("MineCICD initial setup commit")
                                .call();
                        git.push().setCredentialsProvider(getCredentials()).call();
                        try {
                            git.branchCreate().setName(branch).call();
                        } catch (final RefAlreadyExistsException ignored) {
                        }
                        git.checkout().setName(branch).call();

                        if (Config.getBoolean("experimental-jar-loading")) {
                            final File pluginsFolder = new File(new File("."), "plugins");
                            if (pluginsFolder.exists()) {
                                final File[] files = pluginsFolder.listFiles();
                                if (files != null) {
                                    for (final File file : files) {
                                        if (file.getName().endsWith(".jar") && !file.getName().contains("MineCICD")
                                                && !file.getName().contains("PlugMan")) {
                                            String pluginStripped = file.getName();
                                            // up until first "-" or " " or "." or "_"
                                            int index = pluginStripped.indexOf("-");
                                            if (index == -1)
                                                index = pluginStripped.indexOf(" ");
                                            if (index == -1)
                                                index = pluginStripped.indexOf("_");
                                            if (index == -1)
                                                index = pluginStripped.indexOf(".");
                                            if (index != -1) {
                                                pluginStripped = pluginStripped.substring(0, index);
                                            }

                                            // run "plugman unload <plugin>"
                                            final String command = "plugman unload " + pluginStripped;
                                            try {
                                                final String finalPluginStripped = pluginStripped;
                                                MineCICD.plugin.getServer().getScheduler()
                                                        .callSyncMethod(MineCICD.plugin, () -> {
                                                            try {
                                                                MineCICD.plugin.getServer().dispatchCommand(
                                                                        MineCICD.plugin.getServer().getConsoleSender(),
                                                                        command);
                                                            } catch (final Exception e) {
                                                                MineCICD.log("Failed to unload plugin "
                                                                        + finalPluginStripped, Level.SEVERE);
                                                                MineCICD.logError(e);
                                                            }
                                                            return null;
                                                        }).get();
                                            } catch (final Exception e) {
                                                MineCICD.log("Failed to unload plugin " + pluginStripped, Level.SEVERE);
                                                MineCICD.logError(e);
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        git.pull().setStrategy(MergeStrategy.THEIRS).setCredentialsProvider(getCredentials())
                                .setContentMergeStrategy(ContentMergeStrategy.THEIRS).call();

                        if (Config.getBoolean("experimental-jar-loading")) {
                            final File pluginsFolder = new File(new File("."), "plugins");
                            if (pluginsFolder.exists()) {
                                final File[] files = pluginsFolder.listFiles();
                                if (files != null) {
                                    for (final File file : files) {
                                        if (file.getName().endsWith(".jar") && !file.getName().contains("MineCICD")
                                                && !file.getName().contains("PlugMan")) {
                                            String pluginStripped = file.getName();
                                            // up until first "-" or " " or "." or "_"
                                            int index = pluginStripped.indexOf("-");
                                            if (index == -1)
                                                index = pluginStripped.indexOf(" ");
                                            if (index == -1)
                                                index = pluginStripped.indexOf("_");
                                            if (index == -1)
                                                index = pluginStripped.indexOf(".");
                                            if (index != -1) {
                                                pluginStripped = pluginStripped.substring(0, index);
                                            }

                                            // run "plugman unload <plugin>"
                                            final String command = "plugman load " + pluginStripped;
                                            try {
                                                final String finalPluginStripped = pluginStripped;
                                                MineCICD.plugin.getServer().getScheduler()
                                                        .callSyncMethod(MineCICD.plugin, () -> {
                                                            try {
                                                                MineCICD.plugin.getServer().dispatchCommand(
                                                                        MineCICD.plugin.getServer().getConsoleSender(),
                                                                        command);
                                                            } catch (final Exception e) {
                                                                MineCICD.log(
                                                                        "Failed to load plugin " + finalPluginStripped,
                                                                        Level.SEVERE);
                                                                MineCICD.logError(e);
                                                            }
                                                            return null;
                                                        }).get();
                                            } catch (final Exception e) {
                                                MineCICD.log("Failed to load plugin " + pluginStripped, Level.SEVERE);
                                                MineCICD.logError(e);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    final String newCommit = git.log().setMaxCount(1).call().iterator().next().getName();
                    changes = !newCommit.equals(oldCommit);
                }
            } else {
                try (Git git = Git.open(new File("."))) {
                    // fetch which files are going to be changed by pulling (where remote is ahead
                    // of local)
                    final String current = getCurrentRevision();
                    final String latestRemote = getLatestRemoteRevision();
                    final ArrayList<String> toDisable = new ArrayList<>();
                    final ArrayList<String> toEnable = new ArrayList<>();
                    if (Config.getBoolean("experimental-jar-loading")) {
                        if (!current.equals(latestRemote)) {
                            final List<DiffEntry> diffs = getRemoteChanges(git);

                            for (final DiffEntry diff : diffs) {
                                final String path = diff.getNewPath();
                                if (!path.startsWith("plugins/"))
                                    continue;
                                if (!path.endsWith(".jar"))
                                    continue;

                                final File file = new File(path);

                                if (diff.getChangeType() == DiffEntry.ChangeType.ADD
                                        || diff.getChangeType() == DiffEntry.ChangeType.MODIFY
                                        || diff.getChangeType() == DiffEntry.ChangeType.RENAME) {
                                    toEnable.add(file.getName());
                                }

                                if (diff.getChangeType() == DiffEntry.ChangeType.DELETE
                                        || diff.getChangeType() == DiffEntry.ChangeType.MODIFY
                                        || diff.getChangeType() == DiffEntry.ChangeType.RENAME) {
                                    toDisable.add(file.getName());
                                }
                            }

                            if (!toDisable.isEmpty()) {
                                // Disable these plugins
                                for (final String plugin : toDisable) {
                                    String pluginStripped = plugin;
                                    // up until first "-" or " " or "." or "_"
                                    int index = pluginStripped.indexOf("-");
                                    if (index == -1)
                                        index = pluginStripped.indexOf(" ");
                                    if (index == -1)
                                        index = pluginStripped.indexOf("_");
                                    if (index == -1)
                                        index = pluginStripped.indexOf(".");
                                    if (index != -1) {
                                        pluginStripped = pluginStripped.substring(0, index);
                                    } else {
                                        pluginStripped = plugin;
                                    }
                                    // run "plugman unload <plugin>"
                                    final String command = "plugman unload " + pluginStripped;
                                    try {
                                        final String finalPluginStripped = pluginStripped;
                                        MineCICD.plugin.getServer().getScheduler()
                                                .callSyncMethod(MineCICD.plugin, () -> {
                                                    try {
                                                        MineCICD.plugin.getServer().dispatchCommand(
                                                                MineCICD.plugin.getServer().getConsoleSender(),
                                                                command);
                                                    } catch (final Exception e) {
                                                        MineCICD.log("Failed to unload plugin " + finalPluginStripped,
                                                                Level.SEVERE);
                                                        MineCICD.logError(e);
                                                    }
                                                    return null;
                                                }).get();
                                    } catch (final Exception e) {
                                        MineCICD.log("Failed to unload plugin " + pluginStripped, Level.SEVERE);
                                        MineCICD.logError(e);
                                    }
                                }
                            }
                        }
                    }

                    git.pull().setStrategy(MergeStrategy.THEIRS).setCredentialsProvider(getCredentials())
                            .setContentMergeStrategy(ContentMergeStrategy.THEIRS).call();
                    final String newCommit = git.log().setMaxCount(1).call().iterator().next().getName();
                    changes = !newCommit.equals(oldCommit);

                    if (Config.getBoolean("experimental-jar-loading")) {
                        if (!toEnable.isEmpty()) {
                            // Enable these plugins
                            for (final String plugin : toEnable) {
                                String pluginStripped = plugin;
                                // up until first "-" or " " or "." or "_"
                                int index = pluginStripped.indexOf("-");
                                if (index == -1)
                                    index = pluginStripped.indexOf(" ");
                                if (index == -1)
                                    index = pluginStripped.indexOf("_");
                                if (index == -1)
                                    index = pluginStripped.indexOf(".");
                                if (index != -1) {
                                    pluginStripped = pluginStripped.substring(0, index);
                                } else {
                                    pluginStripped = plugin;
                                }
                                // run "plugman load <plugin>"
                                final String command = "plugman load " + pluginStripped;
                                try {
                                    final String finalPluginStripped = pluginStripped;
                                    MineCICD.plugin.getServer().getScheduler().callSyncMethod(MineCICD.plugin, () -> {
                                        try {
                                            MineCICD.plugin.getServer().dispatchCommand(
                                                    MineCICD.plugin.getServer().getConsoleSender(), command);
                                        } catch (final Exception e) {
                                            MineCICD.log("Failed to load plugin " + finalPluginStripped, Level.SEVERE);
                                            MineCICD.logError(e);
                                        }
                                        return null;
                                    }).get();
                                } catch (final Exception e) {
                                    MineCICD.log("Failed to load plugin " + pluginStripped, Level.SEVERE);
                                    MineCICD.logError(e);
                                }
                            }
                        }
                    }
                }
            }

            if (changes) {
                MineCICD.changeBar(bar, getCleanMessage("bossbar-pulled-changes", true), BarColor.GREEN,
                        BarStyle.SOLID);
            } else {
                MineCICD.changeBar(bar, getCleanMessage("bossbar-pulled-no-changes", true), BarColor.GREEN,
                        BarStyle.SOLID);
            }
            MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
            return changes;
        } catch (final Exception e) {
            if (!(e instanceof IllegalStateException)) {
                MineCICD.log("Failed to pull changes", Level.SEVERE);
                MineCICD.logError(e);
                MineCICD.changeBar(bar, getCleanMessage("bossbar-pull-failed", true), BarColor.RED,
                        BarStyle.SEGMENTED_12);
                MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
            } else {
                MineCICD.removeBar(bar, 0);
            }
            throw e;
        } finally {
            if (ownsBusy)
                busyLock = false;
        }
    }

    private static AbstractTreeIterator prepareTreeParser(final Repository repository, final ObjectId objectId)
            throws IOException {
        // Prepare the tree parser
        final CanonicalTreeParser treeParser = new CanonicalTreeParser();
        try (org.eclipse.jgit.revwalk.RevWalk walk = new org.eclipse.jgit.revwalk.RevWalk(repository)) {
            final org.eclipse.jgit.revwalk.RevCommit commit = walk.parseCommit(objectId);
            final org.eclipse.jgit.revwalk.RevTree tree = walk.parseTree(commit.getTree().getId());
            try (org.eclipse.jgit.treewalk.TreeWalk treeWalk = new org.eclipse.jgit.treewalk.TreeWalk(repository)) {
                treeWalk.addTree(tree);
                treeParser.reset(treeWalk.getObjectReader(), tree);
            }
        }
        return treeParser;
    }

    public static void push(final String message, final String author) throws Exception {
        if (!activeRepoExists()) {
            throw new IllegalStateException("Repository has to be pulled (cloned) before changes can be pushed.");
        }

        final boolean ownsBusy = !busyLock;
        if (ownsBusy)
            busyLock = true;

        final String bar = MineCICD.addBar(getCleanMessage("bossbar-pushing", true), BarColor.BLUE, BarStyle.SOLID);

        try {
            // TODO check if all remote commits have been pulled first

            try (Git git = Git.open(new File("."))) {
                git.add().addFilepattern(".").call();

                final boolean changes = !getLocalChanges().isEmpty();
                if (!changes) {
                    MineCICD.changeBar(bar, getCleanMessage("bossbar-push-no-changes", true), BarColor.GREEN,
                            BarStyle.SOLID);
                    MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
                    throw new IllegalStateException("No changes to push");
                }

                final RevCommit commit = git.commit().setAll(true).setAuthor(author, author).setMessage(message).call();
                git.push().add(commit.getName()).setCredentialsProvider(getCredentials()).call();
            }

            MineCICD.changeBar(bar, getCleanMessage("bossbar-pushed", true), BarColor.GREEN, BarStyle.SOLID);
            MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
        } catch (final Exception e) {
            if (!(e instanceof IllegalStateException)) {
                MineCICD.log("Failed to push changes", Level.SEVERE);
                MineCICD.logError(e);
                MineCICD.changeBar(bar, getCleanMessage("bossbar-push-failed", true), BarColor.RED,
                        BarStyle.SEGMENTED_12);
                MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
            }
            throw e;
        } finally {
            if (ownsBusy)
                busyLock = false;
        }
    }

    public static List<String> getIncludedFiles() throws IOException, GitAPIException {
        final List<String> paths = new ArrayList<>();
        try (Git git = Git.open(new File("."))) {
            git.add().addFilepattern(".").call();
            final RevWalk walk = new RevWalk(git.getRepository());
            final RevCommit commit = walk.parseCommit(git.getRepository().resolve("HEAD"));
            final RevTree tree = commit.getTree();

            final TreeWalk treeWalk = new TreeWalk(git.getRepository());
            treeWalk.addTree(tree);
            treeWalk.setRecursive(true);
            while (treeWalk.next()) {
                String path = treeWalk.getPathString();
                if (File.separator.equals("\\")) {
                    path = path.replace("/", "\\");
                } else {
                    path = path.replace("\\", "/");
                }
                paths.add(path);
            }
        }
        return paths;
    }

    public static int add(final File file, final String author) throws GitAPIException, IOException {
        final boolean ownsBusy = !busyLock;
        if (ownsBusy)
            busyLock = true;

        final String bar = MineCICD.addBar(getCleanMessage("bossbar-adding", true), BarColor.BLUE, BarStyle.SOLID);

        try {
            if (!activeRepoExists()) {
                throw new IllegalStateException("Repository has to be pulled (cloned) before files can be added.");
            }

            final File root = new File(".");

            final int before = getIncludedFiles().size();

            String relativePath = root.toPath().toAbsolutePath().relativize(file.toPath().toAbsolutePath()).toString();
            relativePath = relativePath.replace("\\", "/");
            allowInGitIgnore(relativePath, file.isDirectory());

            try (Git git = Git.open(new File("."))) {
                git.add().addFilepattern(".").call();
                final RevCommit commit = git.commit().setAuthor(author, author).setAll(true)
                        .setMessage("MineCICD added \"" + relativePath + "\"").call();
                git.push().add(commit.getName()).setCredentialsProvider(getCredentials()).call();
            }

            final int after = getIncludedFiles().size();
            final int added = after - before;

            MineCICD.changeBar(bar, getCleanMessage("bossbar-added", true, new HashMap<String, String>() {
                {
                    put("amount", String.valueOf(added));
                }
            }), BarColor.GREEN, BarStyle.SOLID);
            MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
            return added;
        } catch (final Exception e) {
            if (!(e instanceof IllegalStateException)) {
                MineCICD.log("Failed to add file(s)", Level.SEVERE);
                MineCICD.logError(e);
                MineCICD.changeBar(bar, getCleanMessage("bossbar-adding-failed", true), BarColor.RED,
                        BarStyle.SEGMENTED_12);
                MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
            } else {
                MineCICD.removeBar(bar, 0);
            }
            throw e;
        } finally {
            if (ownsBusy)
                busyLock = false;
        }
    }

    public static int remove(final File file, final String author) throws GitAPIException, IOException {
        final boolean ownsBusy = !busyLock;
        if (ownsBusy)
            busyLock = true;

        final String bar = MineCICD.addBar(getCleanMessage("bossbar-removing", true), BarColor.BLUE, BarStyle.SOLID);

        try {
            if (!activeRepoExists()) {
                throw new IllegalStateException("Repository has to be pulled (cloned) before files can be removed.");
            }

            final File root = new File(".");

            final int amountBefore = getIncludedFiles().size();

            String relativePath = root.toPath().toAbsolutePath().relativize(file.toPath().toAbsolutePath()).toString();
            relativePath = relativePath.replace("\\", "/");

            int amountAfter;
            try (Git git = Git.open(new File("."))) {
                git.rm().setCached(true).addFilepattern(relativePath).call();
                removeFromGitIgnore(relativePath, file.isDirectory());
                final RevCommit commit = git.commit().setAuthor(author, author).setAll(true)
                        .setMessage("MineCICD removed \"" + relativePath + "\"").call();
                git.push().add(commit.getName()).setCredentialsProvider(getCredentials()).call();
                amountAfter = getIncludedFiles().size();
            }

            final int amountRemoved = amountBefore - amountAfter;

            MineCICD.changeBar(bar, getCleanMessage("bossbar-removed", true, new HashMap<String, String>() {
                {
                    put("amount", String.valueOf(amountRemoved));
                }
            }), BarColor.GREEN, BarStyle.SOLID);
            MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
            return amountRemoved;
        } catch (final Exception e) {
            if (!(e instanceof IllegalStateException)) {
                MineCICD.log("Failed to remove file(s)", Level.SEVERE);
                MineCICD.logError(e);
                MineCICD.changeBar(bar, getCleanMessage("bossbar-removing-failed", true), BarColor.RED,
                        BarStyle.SEGMENTED_12);
                MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
            } else {
                MineCICD.removeBar(bar, 0);
            }
            throw e;
        } finally {
            if (ownsBusy)
                busyLock = false;
        }
    }

    public static void reset(final String commit) throws GitAPIException, IOException {
        if (!activeRepoExists()) {
            throw new IllegalStateException("Repository has to be pulled (cloned) before it can be reset.");
        }

        final boolean ownsBusy = !busyLock;
        if (ownsBusy)
            busyLock = true;

        final String bar = MineCICD.addBar(getCleanMessage("bossbar-resetting", true), BarColor.BLUE, BarStyle.SOLID);

        try (Git git = Git.open(new File("."))) {
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(commit).call();
            MineCICD.changeBar(bar, getCleanMessage("bossbar-reset", true), BarColor.GREEN, BarStyle.SOLID);
            MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
        } catch (final Exception e) {
            if (!(e instanceof IllegalStateException)) {
                MineCICD.log("Failed to reset repository", Level.SEVERE);
                MineCICD.logError(e);
                MineCICD.changeBar(bar, getCleanMessage("bossbar-reset-failed", true), BarColor.RED,
                        BarStyle.SEGMENTED_12);
                MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
            } else {
                MineCICD.removeBar(bar, 0);
            }
            throw e;
        } finally {
            if (ownsBusy)
                busyLock = false;
        }
    }

    public static void revert(final String commit) throws GitAPIException, IOException {
        if (!activeRepoExists()) {
            throw new IllegalStateException("Repository has to be pulled (cloned) before it can be reverted.");
        }

        final boolean ownsBusy = !busyLock;
        if (ownsBusy)
            busyLock = true;

        final String bar = MineCICD.addBar(getCleanMessage("bossbar-reverting", true), BarColor.BLUE, BarStyle.SOLID);

        try (Git git = Git.open(new File("."))) {
            final ObjectId commitId = git.getRepository().resolve(commit);
            final RevCommit revCommit = git.revert().include(commitId).call();
            git.push().add(revCommit.getName()).setCredentialsProvider(getCredentials()).call();
            MineCICD.changeBar(bar, getCleanMessage("bossbar-reverted", true), BarColor.GREEN, BarStyle.SOLID);
            MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
        } catch (final Exception e) {
            if (!(e instanceof IllegalStateException)) {
                MineCICD.log("Failed to revert repository", Level.SEVERE);
                MineCICD.logError(e);
                MineCICD.changeBar(bar, getCleanMessage("bossbar-revert-failed", true), BarColor.RED,
                        BarStyle.SEGMENTED_12);
                MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
            } else {
                MineCICD.removeBar(bar, 0);
            }
            throw e;
        } finally {
            if (ownsBusy)
                busyLock = false;
        }
    }

    public static void rollback(final Calendar calendar) throws GitAPIException, IOException {
        if (!activeRepoExists()) {
            throw new IllegalStateException("Repository has to be pulled (cloned) before it can be rolled back.");
        }

        String lastCommit;
        final long rollbackTime = calendar.getTimeInMillis();
        try (Git git = Git.open(new File("."))) {
            RevCommit commit = git.log().setMaxCount(1).call().iterator().next();
            PersonIdent author = commit.getAuthorIdent();
            Date commitTime = author.getWhen();
            long time = commitTime.getTime();
            if (time <= rollbackTime) {
                lastCommit = commit.getName();
            } else {
                lastCommit = null;
                while (true) {
                    if (commit.getParentCount() == 0) {
                        break;
                    }

                    commit = commit.getParent(0);
                    author = commit.getAuthorIdent();
                    commitTime = author.getWhen();
                    time = commitTime.getTime();
                    if (time <= rollbackTime) {
                        lastCommit = commit.getName();
                        break;
                    }
                }
            }
        }

        if (lastCommit == null) {
            throw new IllegalStateException("No commits found before the specified time");
        }

        reset(lastCommit);
    }

    public static void mergeAbort() throws IOException, GitAPIException {
        try (Git git = Git.open(new File("."))) {
            final Repository repository = git.getRepository();
            repository.writeMergeCommitMsg(null);
            repository.writeMergeHeads(null);
            git.reset().setMode(ResetCommand.ResetType.HARD).call();
        }
    }

    public static void repoReset() {
        FileUtils.deleteQuietly(new File(new File("."), ".git"));
        FileUtils.deleteQuietly(new File(new File("."), ".gitignore"));
    }

    public static RevCommit getCommit(final String commit) throws IOException {
        try (Git git = Git.open(new File("."))) {
            final ObjectId commitId = git.getRepository().resolve(commit);
            if (commitId == null) {
                throw new IllegalArgumentException("Commit not found");
            }

            final RevWalk walk = new RevWalk(git.getRepository());
            return walk.parseCommit(commitId);
        }
    }

    public static void setBranchIfInited() throws IOException, GitAPIException {
        if (!activeRepoExists()) {
            return;
        }

        try (Git git = Git.open(new File("."))) {
            if (git.branchList().call().stream()
                    .noneMatch(ref -> ref.getName().equals("refs/heads/" + Config.getString("git.branch")))) {
                try {
                    git.branchCreate().setName(Config.getString("git.branch")).call();
                } catch (final RefAlreadyExistsException ignored) {
                }
            }

            git.checkout().setName(Config.getString("git.branch")).call();
        }
    }
}
