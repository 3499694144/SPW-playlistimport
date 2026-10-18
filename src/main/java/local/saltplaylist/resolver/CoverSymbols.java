package local.saltplaylist.resolver;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public record CoverSymbols(HostSymbols.Ref directory, HostSymbols.Ref image, HostSymbols.Ref timestamp,
                           HostSymbols.Ref updateBlock, HostSymbols.Ref transaction) {
    public record Resolved(Method directory, Method image, Object imageReader, Method timestamp,
                           Method updateBlock, Method transaction, String sql) {}

    public Resolved restore(ClassLoader loader) throws ReflectiveOperationException {
        Method directoryMethod = directory.restore(loader);
        Method imageMethod = image.restore(loader);
        Method timestampMethod = timestamp.restore(loader);
        Method block = updateBlock.restoreDeclared(loader);
        Method perform = transaction.restore(loader);
        if (!Modifier.isStatic(directoryMethod.getModifiers()) || directoryMethod.getReturnType() != java.io.File.class
                || directoryMethod.getParameterCount() != 0 || Modifier.isStatic(imageMethod.getModifiers())
                || Modifier.isStatic(timestampMethod.getModifiers())) throw new IllegalStateException("Invalid cover handles");
        Object reader = imageMethod.getDeclaringClass().getField("INSTANCE").get(null);
        if (!imageMethod.getDeclaringClass().isInstance(reader)) throw new IllegalStateException("Invalid TagParser instance");
        if (!Modifier.isStatic(block.getModifiers()) || !Modifier.isStatic(perform.getModifiers())
                || !timestampMethod.getDeclaringClass().isAssignableFrom(block.getDeclaringClass())) {
            throw new IllegalStateException("Cover query block does not belong to the playlist DAO implementation");
        }
        block.setAccessible(true);
        return new Resolved(directoryMethod, imageMethod, reader, timestampMethod, block, perform, CoverRules.PREPARED_UPDATE);
    }

    Map<String, HostSymbols.Ref> points() {
        var points = new LinkedHashMap<String, HostSymbols.Ref>();
        points.put("PlaylistCoverDirectory", directory);
        points.put("PlaylistCoverImage", image);
        points.put("PlaylistCoverTimestamp", timestamp);
        points.put("PlaylistCoverUpdateBlock", updateBlock);
        points.put("PlaylistCoverTransaction", transaction);
        return points;
    }

    static CoverSymbols load(Properties properties) {
        return new CoverSymbols(HostSymbols.loadRef(properties, "PlaylistCoverDirectory"),
                HostSymbols.loadRef(properties, "PlaylistCoverImage"), HostSymbols.loadRef(properties, "PlaylistCoverTimestamp"),
                HostSymbols.loadRef(properties, "PlaylistCoverUpdateBlock"), HostSymbols.loadRef(properties, "PlaylistCoverTransaction"));
    }
}
