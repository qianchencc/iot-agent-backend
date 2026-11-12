package com.ehe.iotagent.region;

import java.util.Set;
import com.ehe.iotagent.auth.PrincipalInfo;

/** Current, authorized unread task exceptions; never prepares an upstream catalog inline. */
@FunctionalInterface
public interface RegionAttentionReader {
    Set<String> unreadRegions(PrincipalInfo principal);
}
