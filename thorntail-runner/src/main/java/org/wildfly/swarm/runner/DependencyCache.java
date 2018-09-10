/**
 * Copyright 2018 Red Hat, Inc, and individual contributors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.swarm.runner;

import org.eclipse.aether.util.ChecksumUtils;
import org.wildfly.swarm.tools.ArtifactSpec;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Michal Szynkiewicz, michal.l.szynkiewicz@gmail.com
 * <br>
 * Date: 9/4/18
 * <p>
 * mstodo: split to dedicated caches
 */
public class DependencyCache {

    public static final String CACHE_STORAGE_DIR = ".thorntail-runner-cache";
    private static final String RESOLUTION_CACHE_FILE = "resolution-cache";
    public static final Path CACHE_PATH = Paths.get(CACHE_STORAGE_DIR, RESOLUTION_CACHE_FILE);

    public static final DependencyCache INSTANCE = new DependencyCache();

    private Map<String, File> resolvedCache = new HashMap<>();
    private Set<String> resolutionFailures = new HashSet<>(); // not persisted

    private DependencyCache() {
        long startTime = System.currentTimeMillis();
        System.out.println("Reading and verifying cache");
        Path cache = CACHE_PATH;
        if (!cache.toFile().exists()) {
            System.out.println("No preexisting artifact resolution cache found. The first execution may take some time.");
        } else {
            try {
                lines(cache).forEach(this::addToCache);
            } catch (IOException e) {
                e.printStackTrace();
                System.out.println("Error reading resolution cache file, caching will be disabled.");
            }
        }
        System.out.printf("Cache initialization done in %d [ms]\n", System.currentTimeMillis() - startTime);
    }

    public void store() {
        System.out.println("Storing cache resolution results...");
        try {
            Files.deleteIfExists(CACHE_PATH);
            List<String> lines = resolvedCache.entrySet()
                    .stream()
                    .map(entry -> String.format("%s#%s", entry.getKey(), entry.getValue().getAbsolutePath()))
                    .collect(Collectors.toList());
            storeToFile(CACHE_PATH, lines);
        } catch (IOException e) {
            System.out.println("Failed to store artifact resolution. Next execution won't be able to use full caching capabilities");
            e.printStackTrace();
        }
    }

    private void addToCache(String cacheFileLine) {
        String[] split = cacheFileLine.split("#");
        try {
            String key = split[0];
            String path = split[1];

            File file = Paths.get(path).toFile();
            if (file.exists()) {
                resolvedCache.put(key, file);
            } else {
                System.out.printf("Omitting %s -> %s mapping from cache resolution. It points to a non-existent file", key, path);
            }
        } catch (Exception any) {
            System.out.printf("Omitting invalid cache line %s\n", cacheFileLine);
            any.printStackTrace();
        }
    }


    public List<ArtifactSpec> getCachedDependencies(Collection<ArtifactSpec> specs, boolean defaultExcludes) {
        Path cacheFile = getCacheFile(specs, defaultExcludes);

        return readDependencies(cacheFile);
    }

    public void storeCachedDependencies(Collection<ArtifactSpec> specs, List<ArtifactSpec> dependencySpecs, boolean defaultExcludes) {
        Path cachePath = getCacheFile(specs, defaultExcludes);

        if (cachePath == null) {
            return;
        }

        List<String> linesToStore = dependencySpecs.stream().map(ArtifactSpec::mscGav).collect(Collectors.toList());

        try {
            storeToFile(cachePath, linesToStore);
        } catch (IOException e) {
            System.err.println("Failed to store cached dependencies to " + cachePath.toAbsolutePath().toString());
            e.printStackTrace();
            return;
        }
    }

    private void storeToFile(Path path, List<String> linesToStore) throws IOException {
        File cacheFile = path.toFile();

        File parent = cacheFile.getParentFile();
        if (!parent.exists()) {
            parent.mkdirs();
        }

        try (FileWriter writer = new FileWriter(cacheFile)) {
            for (String line : linesToStore) {
                writer.append(line).append("\n");
            }
        }
    }

    private Path getCacheFile(Collection<ArtifactSpec> specs, boolean defaultExcludes) {
        String key = null;
        try {
            key = getCacheKey(specs);
        } catch (NoSuchAlgorithmException e) {
            System.err.println("No SHA-1 digest algorithm found, caching is disabled");
            return null;
        }

        return Paths.get(CACHE_STORAGE_DIR, key + (defaultExcludes ? "-with-excludes" : ""));
    }


    private List<ArtifactSpec> readDependencies(Path cacheFile) {
        if (cacheFile == null) {
            return null;
        }
        try {
            return lines(cacheFile)
                    .stream()
                    .map(ArtifactSpec::fromMscGav)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return null;
        }
    }

    private List<String> lines(Path input) throws IOException {
        File file = input.toFile();
        try (FileReader fileReader = new FileReader(file);
             BufferedReader reader = new BufferedReader(fileReader)) {
            return reader.lines().collect(Collectors.toList());
        }
    }


    private String getCacheKey(Collection<ArtifactSpec> specs) throws NoSuchAlgorithmException {
        MessageDigest sha1Digest = MessageDigest.getInstance("SHA-1");
        specs.stream()
                .map(ArtifactSpec::jarName)
                .sorted()
                .forEach(
                        jarName -> sha1Digest.update(jarName.getBytes())
                );

        byte[] resultBytes = sha1Digest.digest();

        return ChecksumUtils.toHexString(resultBytes);
    }

    public File getCachedFile(ArtifactSpec spec) {
        String key = spec.mscGav();
        return resolvedCache.get(key);
    }

    public void storeArtifactFile(ArtifactSpec spec, File maybeFile) {
        if (maybeFile != null) {
            resolvedCache.put(spec.mscGav(), maybeFile);
        }
    }

    public Set<String> allResolvedMscGavs() {
        return resolvedCache.keySet();
    }

    public void storeResolutionFailure(ArtifactSpec spec) {
        resolutionFailures.add(spec.mscGav());
    }

    public boolean isFailureToResolveStored(ArtifactSpec spec) {
        return resolutionFailures.contains(spec.mscGav());
    }
}
