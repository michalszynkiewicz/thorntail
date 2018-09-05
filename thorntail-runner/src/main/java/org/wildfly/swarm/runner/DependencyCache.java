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
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Michal Szynkiewicz, michal.l.szynkiewicz@gmail.com
 * <br>
 * Date: 9/4/18
 */
public class DependencyCache {

    public static final String THORNTAIL_RUNNER_CACHE = "thorntail-runner-cache";

    public List<ArtifactSpec> getCachedDependencies(Collection<ArtifactSpec> specs, boolean defaultExcludes) {
        File cacheFile = getCacheFile(specs, defaultExcludes);

        return readDependencies(cacheFile);
    }

    private File getCacheFile(Collection<ArtifactSpec> specs, boolean defaultExcludes) {
        String key = null;
        try {
            key = getCacheKey(specs);
        } catch (NoSuchAlgorithmException e) {
            System.err.println("No SHA-1 digest algorithm found, caching is disabled");
            return null;
        }

        return Paths.get(THORNTAIL_RUNNER_CACHE, key + (defaultExcludes ? "-with-excludes" : "")).toFile();
    }


    private List<ArtifactSpec> readDependencies(File cacheFile) {
        if (cacheFile == null) {
            return null;
        }
        try (FileReader fileReader = new FileReader(cacheFile);
             BufferedReader reader = new BufferedReader(fileReader)) {
            return reader.lines()
                    .filter(line -> !line.isEmpty())
                    .map(ArtifactSpec::fromMscGav)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return null;
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

    public void storeCachedDependencies(Collection<ArtifactSpec> specs, List<ArtifactSpec> dependencySpecs, boolean defaultExcludes) {
        File cacheFile = getCacheFile(specs, defaultExcludes);

        if (cacheFile == null) {
            return;
        }

        File parent = cacheFile.getParentFile();
        if (!parent.exists()) {
            parent.mkdirs();
        }

        try (FileWriter writer = new FileWriter(cacheFile)) {
            for (ArtifactSpec dependencySpec : dependencySpecs) {
                writer.append(dependencySpec.mscGav())
                        .append("\n");
            }
        } catch (IOException e) {
            System.err.println("Failed to store cached dependencies to " + cacheFile.getAbsolutePath());
            e.printStackTrace();
            return;
        }
    }

    public void markResolved(List<ArtifactSpec> dependencyNodes) {
        try {
            File resolvedMark = getResolvedMark(dependencyNodes);
            if (!resolvedMark.exists()) {
                resolvedMark.createNewFile();
            }
        } catch (Exception any) {
            System.err.println("Failed to create a file to mark the the dependencies as resolved");
            any.printStackTrace();
        }
    }


    public boolean areResolved(List<ArtifactSpec> dependencyNodes) {
        try {
            File resolvedMark = getResolvedMark(dependencyNodes);
            return resolvedMark.exists();
        } catch (Exception any) {
            return false;
        }
    }

    private File getResolvedMark(List<ArtifactSpec> dependencyNodes) throws NoSuchAlgorithmException {
        String cacheKey = getCacheKey(dependencyNodes);
        return Paths.get(THORNTAIL_RUNNER_CACHE, cacheKey + "-resolved").toFile();
    }
}
