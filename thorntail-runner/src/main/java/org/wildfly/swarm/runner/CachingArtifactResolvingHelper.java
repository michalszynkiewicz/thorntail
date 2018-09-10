
/**
 * Copyright 2015-2017 Red Hat, Inc, and individual contributors.
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

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.Proxy;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.wildfly.swarm.maven.utils.RepositorySystemSessionWrapper;
import org.wildfly.swarm.tools.ArtifactResolvingHelper;
import org.wildfly.swarm.tools.ArtifactSpec;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * mstodo: policy for releases and snapshots?
 */
public class CachingArtifactResolvingHelper implements ArtifactResolvingHelper {

    public static final String PARALLELISM = "java.util.concurrent.ForkJoinPool.common.parallelism";

    public CachingArtifactResolvingHelper() {
        repoSystem = newRepositorySystem();

        session = newSession(repoSystem);

        this.remoteRepositories.add(buildRemoteRepository(
                "jboss-public-repository-group",
                "https://repository.jboss.org/nexus/content/groups/public/",
                null,
                null));
        this.remoteRepositories.add(buildRemoteRepository(
                "maven-central",
                "https://repo.maven.apache.org/maven2/",
                null,
                null));

        // MSTODO: test by removing central and adding it with a property
        addUserRepositories();
    }

    @Override
    public ArtifactSpec resolve(ArtifactSpec spec) {
        if (spec.file == null) {
            File maybeFile = dependencyCache.getCachedFile(spec);
            if (!dependencyCache.isFailureToResolveStored(spec) && maybeFile == null) {
                System.out.println("no cached file for " + spec.mscGav());
                maybeFile = getArtifactFile(spec);
                dependencyCache.storeArtifactFile(spec, maybeFile);
            }

            if (maybeFile == null) {
                dependencyCache.storeResolutionFailure(spec);
                return null;
            }
            spec.file = maybeFile;
        }

        return spec;
    }

    @Override
    public Set<ArtifactSpec> resolveAll(Collection<ArtifactSpec> specs, boolean transitive, boolean defaultExcludes) throws Exception {
        if (specs.isEmpty()) {
            return Collections.emptySet();
        }
        Collection<ArtifactSpec> toResolve = specs;
        if (transitive) {
            toResolve = resolveDependencies(specs, defaultExcludes);
        }

        long start = System.currentTimeMillis(); // mstodo better time logging
        System.out.println("resolving artifacts");
        String originalPoolSize = System.getProperty(PARALLELISM);
        try {
            // mstodo waaay to slow
            // mstodo try simple parallelism with 20 threads? 10?
            System.setProperty(PARALLELISM, "20");
            Set<ArtifactSpec> result = toResolve.parallelStream()
                    .map(this::resolve)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            return result;
        } finally {
            if (originalPoolSize == null) {
                System.getProperties().remove(PARALLELISM);
            } else {
                System.setProperty(PARALLELISM, originalPoolSize);
            }
            System.out.println("resolving time: " + (System.currentTimeMillis() - start));
        }
    }

    private void addUserRepositories() {
        String repositoriesProperty = System.getProperty("thorntail.runner.repositories");
        if (repositoriesProperty != null) {
            Stream.of(repositoriesProperty.split(","))
                    .forEach(this::addUserRepository);

        }
    }

    private void addUserRepository(String repositoryAsString) {
        String[] split = repositoryAsString.split(":");
        String url = split[0];
        String username = null;
        String password = null;

        if (split.length > 2) {
            username = split[1];
            password = split[2];
        }
        this.remoteRepositories.add(buildRemoteRepository(StringUtils.randomAlphabetic(8), url, username, password));
    }

    private static RepositorySystem newRepositorySystem() {
        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, FileTransporterFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);

        return locator.getService(RepositorySystem.class);
    }

    private static RepositorySystemSession newSession(RepositorySystem system) {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();

        LocalRepository localRepo = new LocalRepository(localRepoLocation());
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));

        return session;
    }

    private static File localRepoLocation() {
        File result;
        String userRepository = System.getProperty("thorntail.runner.local-repository");
        if (userRepository != null) {
            result = new File(userRepository);
            if (!result.isDirectory()) {
                System.err.println("The defined local repository: " + userRepository + " does not exist or is not a directory");
            }
        } else {
            String userHome = System.getProperty("user.home");
            result = Paths.get(userHome, ".m2", "repository").toFile();      // mstodo maybe use thorntail-runner-cache if it does not exist?
            // mstodo check if exists, ask user to create or point to a different location?
        }
        return result;
    }

    private Collection<ArtifactSpec> resolveDependencies(final Collection<ArtifactSpec> specs, boolean defaultExcludes) throws DependencyCollectionException {
        long start = System.currentTimeMillis();
        List<ArtifactSpec> dependencyNodes = dependencyCache.getCachedDependencies(specs, defaultExcludes);
        if (dependencyNodes == null) {
            List<Dependency> dependencies =
                    specs.stream()
                            .map(this::artifact)
                            .map(a -> new Dependency(a, "compile"))
                            .collect(Collectors.toList());

            CollectRequest collectRequest = new CollectRequest(dependencies, null, remoteRepositories);


            RepositorySystemSession session = new RepositorySystemSessionWrapper(this.session, defaultExcludes);
            CollectResult result = this.repoSystem.collectDependencies(session, collectRequest);
            PreorderNodeListGenerator gen = new PreorderNodeListGenerator();
            result.getRoot().accept(gen);
            dependencyNodes = gen.getNodes()
                    .stream()
                    .map(
                            node -> {
                                Artifact artifact = node.getArtifact();
                                return new ArtifactSpec(node.getDependency().getScope(),
                                        artifact.getGroupId(),
                                        artifact.getArtifactId(),
                                        artifact.getVersion(),
                                        artifact.getExtension(),
                                        artifact.getClassifier(),
                                        artifact.getFile());
                            }
                    ).collect(Collectors.toList());


            dependencyCache.storeCachedDependencies(specs, dependencyNodes, defaultExcludes);
        }
        System.out.println("dependency analysis time: " + (System.currentTimeMillis() - start) + "[ms]");
        start = System.currentTimeMillis();

        Collection<ArtifactSpec> result = resolveDependencies(dependencyNodes);
        System.out.println("dependency resolution time: " + (System.currentTimeMillis() - start) + "[ms]");
        return result;
    }

    private Collection<ArtifactSpec> resolveDependencies(List<ArtifactSpec> dependencyNodes) {
        long start = System.currentTimeMillis();
        // if dependencies were previously resolved, we don't need to resolve using remote repositories
        Set<String> resolvedGavs = dependencyCache.allResolvedMscGavs();

        dependencyNodes = new ArrayList<>(dependencyNodes);
        dependencyNodes.removeIf(
                spec -> resolvedGavs.contains(spec.mscGav())
        );

        System.out.println("parallel resolution time: " + (System.currentTimeMillis() - start));

        try {
            return dependencyNodes.parallelStream()
                    .filter(node -> !"system".equals(node.scope))
                    .map(node -> new ArtifactSpec(node.scope,
                            node.groupId(),
                            node.artifactId(),
                            node.version(),
                            "bundle".equals(node.type()) ? "jar" : node.type(),
                            node.classifier(),
                            null))
                    .map(this::resolve)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        } finally {
            System.out.println("total time of dep resolution: " + (System.currentTimeMillis() - start));
        }
    }

    private DefaultArtifact artifact(ArtifactSpec spec) {
        String type = spec.type();
        type = "bundle".equals(type) ? "jar" : type;
        return new DefaultArtifact(spec.groupId(), spec.artifactId(), spec.classifier(),
                type, spec.version());
    }

    // mstodo simplify
    private File getArtifactFile(ArtifactSpec spec) {
        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(artifact(spec));
        ArtifactResult artifactResult = null;
        // try local resolution first, fallback to remote repos if not found
        try {
            artifactResult = repoSystem.resolveArtifact(session, request);
        } catch (ArtifactResolutionException ignored) {
            remoteRepositories.forEach(request::addRepository);
            try {
                artifactResult = repoSystem.resolveArtifact(session, request);
            } catch (ArtifactResolutionException e) {
                e.printStackTrace();
            }
        }

        if (artifactResult != null && artifactResult.isResolved()) {
            return artifactResult.getArtifact().getFile();
        } else {
            return null;
        }
    }


    private RemoteRepository buildRemoteRepository(final String id, final String url, final String username, final String password) {
        RemoteRepository.Builder builder = new RemoteRepository.Builder(id, "default", url);
        if (username != null) {
            builder.setAuthentication(new AuthenticationBuilder()
                    .addUsername(username)
                    .addPassword(password).build());
        }

        RemoteRepository repository = builder.build();

        final RemoteRepository mirror = session.getMirrorSelector().getMirror(repository);

        if (mirror != null) {
            final org.eclipse.aether.repository.Authentication mirrorAuth = session.getAuthenticationSelector()
                    .getAuthentication(mirror);
            RemoteRepository.Builder mirrorBuilder = new RemoteRepository.Builder(mirror)
                    .setId(repository.getId());
            if (mirrorAuth != null) {
                mirrorBuilder.setAuthentication(mirrorAuth);
            }
            repository = mirrorBuilder.build();
        }

        Proxy proxy = session.getProxySelector().getProxy(repository);

        if (proxy != null) {
            repository = new RemoteRepository.Builder(repository).setProxy(proxy).build();
        }

        return repository;
    }

    private final DependencyCache dependencyCache = DependencyCache.INSTANCE;
    private final List<RemoteRepository> remoteRepositories = new ArrayList<>();
    private final RepositorySystem repoSystem;
    private final RepositorySystemSession session;

}
