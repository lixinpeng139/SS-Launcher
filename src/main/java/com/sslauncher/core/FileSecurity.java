package com.sslauncher.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

/**
 * Restricts sensitive files (account tokens, for example) to the current user.
 *
 * <p>Windows gets a one-entry ACL for the file owner. Other systems use POSIX
 * {@code rw-------}. Permission tightening is best effort: an exotic or
 * read-only filesystem must not prevent the launcher from saving data, so
 * failures are logged and reported through the return value.</p>
 */
public final class FileSecurity {
    private FileSecurity() {
    }

    public static boolean restrict(Path file) {
        if (file == null) {
            return false;
        }
        try {
            if (!Files.exists(file)) {
                return false;
            }
            if ("windows".equals(AppPaths.osName())) {
                return restrictWindows(file);
            }
            return restrictPosix(file);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Log.warn("无法收紧敏感文件权限 " + file + ": " + e.getMessage());
            return false;
        }
    }

    private static boolean restrictWindows(Path file) throws IOException {
        AclFileAttributeView view =
                Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (view == null) {
            Log.warn("文件系统不支持 ACL，无法收紧敏感文件权限: " + file);
            return false;
        }
        UserPrincipal owner = view.getOwner();
        AclEntry ownerOnly = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
        view.setAcl(List.of(ownerOnly));
        return true;
    }

    private static boolean restrictPosix(Path file) throws IOException {
        PosixFileAttributeView view =
                Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (view == null) {
            Log.warn("文件系统不支持 POSIX 权限，无法收紧敏感文件权限: " + file);
            return false;
        }
        view.setPermissions(PosixFilePermissions.fromString("rw-------"));
        return true;
    }
}
