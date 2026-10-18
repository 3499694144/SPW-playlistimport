package local.saltplaylist.resolver;

import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.LinkedHashMap;
import java.util.Collections;

/** Persisted descriptors, followed by signature-checked runtime handles. */
public record HostSymbols(Ref databaseProvider, Ref playlistDao, Ref trackDao,
                          Ref readTracks, Ref createPlaylist, CoverSymbols cover,
                          ImportUiSymbols ui, FilePickerSymbols picker) {
    public HostSymbols(Ref provider, Ref playlists, Ref tracks, Ref read, Ref create) {
        this(provider, playlists, tracks, read, create, null, null, null);
    }

    HostSymbols withFeatures(CoverSymbols cover, ImportUiSymbols ui, FilePickerSymbols picker) {
        return new HostSymbols(databaseProvider, playlistDao, trackDao, readTracks, createPlaylist, cover, ui, picker);
    }

    public record Ref(String owner, String name, String descriptor) {
        public Method restore(ClassLoader loader) throws ReflectiveOperationException {
            Method method = restoreDeclared(loader);
            if (!Modifier.isPublic(method.getModifiers())) throw new NoSuchMethodException("Not public: " + this);
            return method;
        }

        public Method restoreDeclared(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> type = Class.forName(owner, false, loader);
            MethodType signature = MethodType.fromMethodDescriptorString(descriptor, loader);
            Method method = type.getDeclaredMethod(name, signature.parameterArray());
            if (method.getReturnType() != signature.returnType()) {
                throw new NoSuchMethodException("Signature/access mismatch: " + this);
            }
            return method;
        }

        public Constructor<?> restoreConstructor(ClassLoader loader) throws ReflectiveOperationException {
            MethodType signature = MethodType.fromMethodDescriptorString(descriptor, loader);
            if (!name.equals("<init>") || signature.returnType() != void.class) throw new NoSuchMethodException("Invalid constructor: " + this);
            return Class.forName(owner, false, loader).getConstructor(signature.parameterArray());
        }
        @Override public String toString() { return owner + "#" + name + descriptor; }
    }

    public record Resolved(HostSymbols descriptors, Method databaseProvider, Method playlistDao,
                           Method trackDao, Method readTracks, Method createPlaylist,
                           Class<?> trackType, Method trackId, Method trackPath,
                           Method trackReadable, Class<?> playlistType, Method playlistId,
                           CoverSymbols.Resolved cover, ImportUiSymbols.Resolved ui, FilePickerSymbols.Resolved picker) {
        public Class<?> databaseType() { return databaseProvider.getReturnType(); }
    }

    public Resolved restore(ClassLoader loader) throws ReflectiveOperationException {
        Method provider = databaseProvider.restore(loader);
        Method playlists = playlistDao.restore(loader);
        Method tracks = trackDao.restore(loader);
        Method read = readTracks.restore(loader);
        Method create = createPlaylist.restore(loader);
        if (!Modifier.isStatic(provider.getModifiers()) || provider.getParameterCount() != 0
                || playlists.getDeclaringClass() != provider.getReturnType()
                || tracks.getDeclaringClass() != provider.getReturnType()
                || playlists.getReturnType() != create.getDeclaringClass()
                || tracks.getReturnType() != read.getDeclaringClass()) {
            throw new IllegalStateException("Cached database/DAO relationships do not match");
        }
        Class<?> trackType = Class.forName(PlaylistRules.TRACK_CLASS, false, loader);
        Class<?> playlistType = Class.forName(PlaylistRules.PLAYLIST_CLASS, false, loader);
        CoverSymbols.Resolved coverHandles = cover.restore(loader);
        if (coverHandles.timestamp().getDeclaringClass() != create.getDeclaringClass()) {
            throw new IllegalStateException("Cover timestamp does not belong to the playlist DAO");
        }
        return new Resolved(this, provider, playlists, tracks, read, create, trackType,
                getter(trackType, "getId", String.class), getter(trackType, "getPath", String.class),
                getter(trackType, "getReadable", boolean.class), playlistType,
                getter(playlistType, "getId", String.class), coverHandles, ui.restore(loader), picker.restore(loader));
    }

    private static Method getter(Class<?> type, String name, Class<?> returns) throws NoSuchMethodException {
        Method method = type.getMethod(name);
        if (method.getReturnType() != returns || Modifier.isStatic(method.getModifiers())) {
            throw new NoSuchMethodException("Invalid entity getter: " + type.getName() + "#" + name);
        }
        return method;
    }

    public Map<String, Ref> points() {
        var result = new LinkedHashMap<String, Ref>();
        result.put("DatabaseCapture", databaseProvider);
        result.put("PlaylistDao", playlistDao);
        result.put("TrackDao", trackDao);
        result.put("TrackSnapshot", readTracks);
        result.put("CreatePlaylist", createPlaylist);
        if (cover != null) result.putAll(cover.points());
        if (ui != null) result.putAll(ui.points());
        if (picker != null) result.putAll(picker.points());
        return Collections.unmodifiableMap(result);
    }

    public void save(Properties properties) {
        points().forEach((key, ref) -> {
            properties.setProperty(key + ".owner", ref.owner());
            properties.setProperty(key + ".name", ref.name());
            properties.setProperty(key + ".descriptor", ref.descriptor());
        });
    }

    public static HostSymbols load(Properties properties) {
        return new HostSymbols(loadRef(properties, "DatabaseCapture"), loadRef(properties, "PlaylistDao"),
                loadRef(properties, "TrackDao"), loadRef(properties, "TrackSnapshot"),
                loadRef(properties, "CreatePlaylist"), CoverSymbols.load(properties), ImportUiSymbols.load(properties), FilePickerSymbols.load(properties));
    }

    static Ref loadRef(Properties properties, String key) {
        List<String> values = List.of(key + ".owner", key + ".name", key + ".descriptor").stream()
                .map(name -> {
                    String value = properties.getProperty(name);
                    if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing cache field " + name);
                    return value;
                }).toList();
        return new Ref(values.get(0), values.get(1), values.get(2));
    }
}
