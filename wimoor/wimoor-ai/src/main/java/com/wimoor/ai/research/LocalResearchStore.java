package com.wimoor.ai.research;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 设备本地 CLI 文件存储（移植自 src/lib/selection-research/file-store.ts）。
 * POSIX 所有者/权限位 + 目录锁 + 临时文件 fsync 后原子改名，是"存储私密且提交可恢复"的可移植证明。
 */
public final class LocalResearchStore implements ResearchStore {

    private static final java.util.regex.Pattern RUN_ID = java.util.regex.Pattern.compile("^[a-zA-Z0-9_-]{1,80}$");
    private static final boolean POSIX = posixSupported();
    private final Path root;

    public LocalResearchStore(String root) {
        this.root = Paths.get(root).toAbsolutePath();
    }

    private Path folder(String id) {
        if (!RUN_ID.matcher(id).matches()) throw new ResearchError("INVALID_RUN_ID");
        return root.resolve(id);
    }

    private void check(Path dir) {
        try {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(dir)) throw new ResearchStoreException("UNSAFE_LOCAL_STORE");
            if (POSIX && Files.getFileAttributeView(dir, PosixFileAttributeView.class) != null) {
                PosixFileAttributes attrs = Files.readAttributes(dir, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                String owner = attrs.owner().getName();
                String current = System.getProperty("user.name");
                boolean groupOther = attrs.permissions().stream().anyMatch(p -> p.name().startsWith("GROUP") || p.name().startsWith("OTHERS"));
                if (!owner.equals(current) || groupOther) throw new ResearchStoreException("UNSAFE_LOCAL_STORE");
            }
        } catch (IOException e) {
            throw new ResearchStoreException("UNSAFE_LOCAL_STORE", e);
        }
    }

    @Override
    public void create(ResearchTypes.ResearchRun run) {
        try {
            Files.createDirectories(root);
            check(root);
            Path folder = folder(run.id);
            Files.createDirectory(folder);
            persist(folder, run);
        } catch (IOException e) {
            throw new ResearchStoreException("RESEARCH_STORE_IO", e);
        }
    }

    @Override
    public ResearchTypes.ResearchRun read(String id) {
        Path folder = folder(id);
        check(root);
        check(folder);
        Path state = folder.resolve("state.json");
        try {
            if (Files.isSymbolicLink(state)) throw new ResearchStoreException("UNSAFE_LOCAL_STORE");
            byte[] bytes = Files.readAllBytes(state);
            ResearchTypes.ResearchRun run = ResearchJson.MAPPER.readValue(bytes, ResearchTypes.ResearchRun.class);
            if (run.version != 1 || !id.equals(run.id)) throw new ResearchStoreException("UNSUPPORTED_STATE");
            return run;
        } catch (IOException e) {
            throw new ResearchStoreException("RESEARCH_STORE_IO", e);
        }
    }

    private void persist(Path folder, ResearchTypes.ResearchRun run) {
        try {
            byte[] bytes = ResearchJson.MAPPER.writeValueAsBytes(run);
            Path temp = folder.resolve(UUID.randomUUID() + ".tmp");
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            try {
                Files.move(temp, folder.resolve("state.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, folder.resolve("state.json"), StandardCopyOption.REPLACE_EXISTING);
            }
            if (POSIX) {
                try (FileChannel dir = FileChannel.open(folder, StandardOpenOption.READ)) { dir.force(true); }
            }
        } catch (IOException e) {
            throw new ResearchStoreException("RESEARCH_STORE_IO", e);
        }
    }

    @Override
    public <T> T transaction(String id, Work<T> work) {
        Path folder = folder(id);
        check(root);
        check(folder);
        Path lock = folder.resolve(".lock");
        try {
            Files.createDirectory(lock);
        } catch (IOException e) {
            throw new ResearchStoreException("RUN_LOCKED_USE_EXPLICIT_RECOVERY", e);
        }
        try {
            try {
                Map<String, Object> owner = Map.of("pid", ProcessHandle.current().pid(), "host", hostName());
                byte[] bytes = ResearchJson.MAPPER.writeValueAsBytes(owner);
                Path file = lock.resolve("owner.json");
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    channel.write(ByteBuffer.wrap(bytes));
                    channel.force(true);
                }
            } catch (IOException e) {
                throw new ResearchStoreException("RESEARCH_STORE_IO", e);
            }
            ResearchTypes.ResearchRun run = read(id);
            return work.apply(run, () -> {
                run.revision++;
                run.updatedAt = Instant.now().toString();
                persist(folder, run);
            });
        } finally {
            try {
                Files.walk(lock).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { /* 清理失败不影响事务结果 */ }
                });
            } catch (IOException ignored) {
                // 锁目录清理失败由显式恢复处理
            }
        }
    }

    /** 仅显式本地恢复：绝不抢占仍然存活或来源不明的锁。 */
    public void recoverDeadProcessLock(String id) {
        Path folder = folder(id);
        check(root);
        check(folder);
        Path ownerFile = folder.resolve(".lock").resolve("owner.json");
        try {
            Map<?, ?> owner = ResearchJson.MAPPER.readValue(ownerFile.toFile(), Map.class);
            Object pidObj = owner.get("pid");
            if (!hostName().equals(owner.get("host")) || !(pidObj instanceof Number)
                    || ((Number) pidObj).longValue() <= 0) throw new ResearchStoreException("LOCK_OWNER_UNKNOWN");
            long pid = ((Number) pidObj).longValue();
            if (ProcessHandle.of(pid).isPresent()) throw new ResearchStoreException("LOCK_OWNER_ALIVE");
            Files.walk(folder.resolve(".lock")).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException e) {
            throw new ResearchStoreException("LOCK_OWNER_UNKNOWN", e);
        }
    }

    private static String hostName() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "unknown"; }
    }

    private static boolean posixSupported() {
        try { return Files.getFileStore(Paths.get(".").toAbsolutePath()).supportsFileAttributeView("posix"); }
        catch (IOException e) { return false; }
    }
}
