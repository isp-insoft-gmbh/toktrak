package toktrak.tests;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirFactory;

/// A temporary directory on the real disk, only for tests of real file-system guarantees such as
/// cross-process locks; every other `@TempDir` is in memory.
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@TempDir(factory = TempDirFactory.Standard.class)
@interface DiskTempDir {}
