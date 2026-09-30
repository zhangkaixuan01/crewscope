package io.crewscope.server;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.transaction.annotation.Transactional;

/**
 * A final class carrying {@link Transactional} (on the class or any method) cannot get its CGLIB
 * transaction proxy, which kills the whole application context at startup. The sliced and mocked
 * unit suites never assemble that proxy, so the failure only surfaces on a real stack boot —
 * exactly what the M7-Q03/M9b-Q01 gates exist to catch. This structural guard keeps the failure
 * in the fast tier. Run under the Maven reactor (plain {@code ./mvnw test} or {@code -am}); a
 * lone {@code -pl crewscope-server} run reads stale SNAPSHOT jars from the local repository and
 * can report an already-fixed class.
 */
class TransactionalClassesStayProxiableTest {

    @Test
    void noFinalClassCarriesTransactional() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        CachingMetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);
        List<String> offenders = new ArrayList<>();
        int scannedClasses = 0;
        int transactionalClasses = 0;
        for (Resource resource : resolver.getResources("classpath*:io/crewscope/**/*.class")) {
            if (!resource.isReadable()) continue;
            MetadataReader reader = readers.getMetadataReader(resource);
            scannedClasses += 1;
            if (isTransactional(reader.getAnnotationMetadata())) {
                transactionalClasses += 1;
                Class<?> bean = Class.forName(reader.getClassMetadata().getClassName());
                if (Modifier.isFinal(bean.getModifiers())) offenders.add(bean.getName());
            }
        }
        // Sanity so an empty scan cannot pass vacuously: the backend has hundreds of classes and
        // dozens of transactional ones on every module's classpath.
        assertTrue(scannedClasses > 500, "classpath scan reached only " + scannedClasses + " classes");
        assertTrue(transactionalClasses > 10, "scan found only " + transactionalClasses + " transactional classes");
        assertTrue(offenders.isEmpty(),
                "final @Transactional classes cannot be proxied and abort context startup: " + offenders);
    }

    private static boolean isTransactional(AnnotationMetadata metadata) {
        return metadata.isAnnotated(Transactional.class.getName())
                || !metadata.getAnnotatedMethods(Transactional.class.getName()).isEmpty();
    }
}
