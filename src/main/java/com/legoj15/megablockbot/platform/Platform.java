package com.legoj15.megablockbot.platform;

/**
 * Compile-time binder: one static accessor per platform service, each backed by its own lazy holder, so asking for one
 * service never loads another's implementation. The loader arms in the holders are the only loader directives outside
 * {@code platform/<loader>/}.
 */
public final class Platform {
    private Platform() {}

    public static LoaderServices loader() {
        return LoaderHolder.INSTANCE;
    }

    private static final class LoaderHolder {
        static final LoaderServices INSTANCE =
            //? if neoforge {
            new com.legoj15.megablockbot.platform.neoforge.NeoForgeLoaderServices();
            //?}
    }
}
