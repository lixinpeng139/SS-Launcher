package com.sslauncher.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FileSecurityTest {
    @TempDir
    Path temp;

    @Test
    void restrictsFileToCurrentUser() throws Exception {
        Path file = temp.resolve("accounts.json");
        Files.writeString(file, "{\"accessToken\":\"secret\"}", StandardCharsets.UTF_8);

        assertTrue(FileSecurity.restrict(file));
        assertOwnerOnly(file);
    }

    @Test
    void privateAtomicWriteLeavesNoTemporaryFileAndRestrictsTarget() throws Exception {
        Path file = temp.resolve("private").resolve("accounts.json");
        byte[] data = "{\"refreshToken\":\"sealed\"}".getBytes(StandardCharsets.UTF_8);

        AppPaths.atomicWritePrivate(file, data);

        assertArrayEquals(data, Files.readAllBytes(file));
        assertOwnerOnly(file);
        try (var files = Files.list(file.getParent())) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith(".ss-private-")));
        }
    }

    private static void assertOwnerOnly(Path file) throws Exception {
        if ("windows".equals(AppPaths.osName())) {
            AclFileAttributeView view =
                    Files.getFileAttributeView(file, AclFileAttributeView.class);
            assertNotNull(view);
            List<AclEntry> acl = view.getAcl();
            assertEquals(1, acl.size());
            AclEntry entry = acl.get(0);
            assertEquals(AclEntryType.ALLOW, entry.type());
            assertEquals(view.getOwner(), entry.principal());
            assertTrue(entry.permissions()
                    .containsAll(EnumSet.allOf(AclEntryPermission.class)));
        } else {
            PosixFileAttributeView view =
                    Files.getFileAttributeView(file, PosixFileAttributeView.class);
            assumeTrue(view != null, "filesystem has no POSIX permission view");
            assertEquals("rw-------",
                    PosixFilePermissions.toString(view.readAttributes().permissions()));
        }
    }
}
