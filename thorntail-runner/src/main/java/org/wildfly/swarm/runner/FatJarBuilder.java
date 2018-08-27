/**
 * Copyright 2018 Red Hat, Inc, and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.swarm.runner;

import org.w3c.dom.Document;
import org.wildfly.swarm.spi.meta.SimpleLogger;
import org.wildfly.swarm.tools.ArtifactResolvingHelper;
import org.wildfly.swarm.tools.ArtifactSpec;
import org.wildfly.swarm.tools.BuildTool;
import org.wildfly.swarm.tools.DeclaredDependencies;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static org.wildfly.swarm.runner.StringUtils.randomAlphabetic;

/**
 * mstodo: try on license dictionary
 *
 * @author Michal Szynkiewicz, michal.l.szynkiewicz@gmail.com
 * <br>
 * Date: 7/30/18
 */
public class FatJarBuilder {

    public static final String FAKE_GROUP_ID = "com.fakegroupid";
    private final List<URL> classPathUrls;
    private final File target;

    public FatJarBuilder(List<URL> classPathUrls, File target) {
        this.classPathUrls = classPathUrls;
        this.target = target;
    }

    public static void main(String[] args) throws Exception {
        File fatJar = new File(args[0]);

        long start = System.currentTimeMillis();

        buildFatJarTo(fatJar);

        System.out.printf("total time %d ms\n", System.currentTimeMillis() - start);
    }

    private static File buildFatJarTo(File target) throws Exception {
        List<URL> urls = getClasspathUrls();

        FatJarBuilder b = new FatJarBuilder(urls, target);
        return b.doBuild();
    }

    private static List<URL> getClasspathUrls() throws MalformedURLException {
        URLClassLoader urlLoader = (URLClassLoader) ClassLoader.getSystemClassLoader();
        List<String> urlList =
                Stream.of(urlLoader.getURLs())
                        .map(URL::toString)
                        .collect(Collectors.toList());

        List<URL> urls = new ArrayList<>();

        for (String urlString : urlList) {
            urls.add(new URL(urlString));
        }
        return urls;
    }

    private File doBuild() throws Exception {
        final String type = "war";

        // TODO: NOTE: we don't know which deps are transitive!!!
        long start = System.currentTimeMillis();
        List<ArtifactOrFile> classPathEntries = analyzeClasspath();
        System.out.println("Classpath analyzing time: " + (System.currentTimeMillis() - start + " ms"));

        File war = buildWar(classPathEntries);
        final BuildTool tool = new BuildTool(mavenArtifactResolvingHelper(), true)
                .projectArtifact("tt",
                        "tt-user-app",
                        "0.1-SNAPSHOT",
                        type,
                        war,
                        "artificial.war")// mstodo
                .properties(System.getProperties())
//                .mainClass(this.mainClass)
//                .bundleDependencies(this.bundleDependencies)
                .fractionDetectionMode(BuildTool.FractionDetectionMode.when_missing)
                .hollow(false)
                .logger(new SimpleLogger() {
                    // mstodo proper logging ?
                    @Override
                    public void debug(String msg) {
                        System.out.println(msg);
                    }

                    @Override
                    public void info(String msg) {
                        System.out.println(msg);
                    }

                    @Override
                    public void error(String msg) {
                        System.out.println(msg);
                    }

                    @Override
                    public void error(String msg, Throwable t) {
                        System.out.println(msg);
                        t.printStackTrace();
                    }
                });

//        mstodo replace with system or env property
//        this.fractions.forEach(f -> {
//            if (f.startsWith(EXCLUDE_PREFIX)) {
//                tool.excludeFraction(ArtifactSpec.fromFractionDescriptor(FractionDescriptor.fromGav(FractionList.get(), f.substring(1))));
//            } else {
//                tool.fraction(ArtifactSpec.fromFractionDescriptor(FractionDescriptor.fromGav(FractionList.get(), f)));
//            }
//        });

//        Map<ArtifactSpec, Set<ArtifactSpec>> buckets = createBuckets(this.project.getArtifacts(), this.project.getDependencies());


        tool.declaredDependencies(declaredDependencies(classPathEntries));


        // mstodo can any non-file url get here?
        this.classPathUrls.parallelStream()
                .filter(url -> !url.toString().matches(".*\\.[^/]*"))
                .forEach(r -> tool.resourceDirectory(r.getFile()));

        // mstodo replace/remove
        Path uberjarResourcesDir = File.createTempFile("uberjar-resources-placehodler", "bs").toPath();
        tool.uberjarResourcesDirectory(uberjarResourcesDir);

        File jar = tool.build(target.getName(), target.getParentFile().toPath());


        tool.repackageWar(war);
        return jar;
    }

    /**
     * builds war with classes inside
     *
     * @param classPathEntries class path entries as ArtifactSpec or URLs
     * @return the war file
     */
    private File buildWar(List<ArtifactOrFile> classPathEntries) {
        try {
            List<String> classesUrls = classPathEntries.stream()
                    .map(ArtifactOrFile::file)
                    .filter(this::isDirectory)
                    .filter(url -> url.contains("classes"))
                    .collect(Collectors.toList());

            List<File> classpathJars = classPathEntries.stream()
                    .map(ArtifactOrFile::file)
                    .filter(file -> file.endsWith(".jar"))
                    .map(File::new)
                    .collect(Collectors.toList());


            return WarBuilder.build(classesUrls, classpathJars);
        } catch (IOException e) {
            throw new RuntimeException("failed to build war", e);
        }
    }

    private boolean isDirectory(String filePath) {
        return Files.isDirectory(Paths.get(filePath));
    }


    private ArtifactResolvingHelper mavenArtifactResolvingHelper() {
        // all artifacts should have files defined, no need to resolve anything
        return new ArtifactResolvingHelper() {
            @Override
            public ArtifactSpec resolve(ArtifactSpec spec) throws Exception {
                return spec;
            }

            @Override
            public Set<ArtifactSpec> resolveAll(Collection<ArtifactSpec> specs, boolean transitive, boolean defaultExcludes) throws Exception {
                return new HashSet<>(specs);
            }
        };
    }

    private List<ArtifactOrFile> analyzeClasspath() {
        return classPathUrls.parallelStream()
                .filter(this::notJdkJar)
                .map(this::urlToSpec)
                .collect(toList());
    }

    private boolean notJdkJar(URL url) {
        return !url.toString().contains(System.getProperty("java.home"));
    }

    private DeclaredDependencies declaredDependencies(List<ArtifactOrFile> specsOrUrls) {
        List<ArtifactSpec> specs =
                specsOrUrls.stream()
                        .filter(ArtifactOrFile::isJar)
                        .filter(ArtifactOrFile::hasSpec)
                        .map(specOrUrl -> specOrUrl.spec)
                        .collect(toList());
        return new DeclaredDependencies() {
            @Override
            public Collection<ArtifactSpec> getDirectDeps() {
                return specs;
            }

            @Override
            public Collection<ArtifactSpec> getTransientDeps(ArtifactSpec parent) {
                return Collections.emptyList();
            }
        };
    }

    private ArtifactOrFile urlToSpec(URL url) {
        String file = resolveUrlToFile(url);
        if (!url.toString().endsWith(".jar")) {
            return ArtifactOrFile.file(file);
        }
        try (FileSystem fs = FileSystems.newFileSystem(Paths.get(file), getClass().getClassLoader())) {
            Optional<Path> maybePomXml = findPom(fs);

            ArtifactSpec spec = maybePomXml
                    .map(pom -> toArtifactSpec(pom, file))
                    .orElse(mockArtifactSpec(file));
            return ArtifactOrFile.spec(spec);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse jar: " + file, e);
        }
    }

    private Optional<Path> findPom(FileSystem fs) throws IOException {
        Optional<Path> maybePomXml;
        Path path = fs.getPath("/META-INF/maven");

        if (path == null || !Files.exists(path)) {
            maybePomXml = Optional.empty();
        } else {

            /*mstodo: switch this to getPathMatcher?*/
            maybePomXml = Files.walk(path)
                    .filter(p -> p.endsWith("pom.xml"))
                    .findAny();
        }
        return maybePomXml;
    }

    private ArtifactSpec mockArtifactSpec(String jarPath) {
        return new ArtifactSpec("compile",
                FAKE_GROUP_ID, randomAlphabetic(10), "0.0.1", "jar", null,
                new File(jarPath));
    }


    private ArtifactSpec toArtifactSpec(Path pom, String jarPath) {
        try {
            // mstodo watch out for properties?
            String groupId = extract(pom, "/project/groupId",
                    () -> extract(pom, "/project/parent/groupId"));
            String artifactId = extract(pom, "/project/artifactId");
            String version = extract(pom, "/project/version",
                    () -> extract(pom, "/project/parent/version"));
            String packaging = extract(pom, "/project/packaging", "jar");
            String classifier = extract(pom, "/project/classifier", (String) null);

            return new ArtifactSpec("compile",
                    groupId, artifactId, version, packaging, classifier,
                    new File(jarPath));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read artifact spec from pom " + pom, e);
        }
    }

    private String extract(Path source, String expression, Supplier<String> defaultValueProvider) {
        String extracted = extract(source, expression);

        return extracted == null || "".equals(extracted)
                ? defaultValueProvider.get()
                : extracted;
    }

    private String extract(Path source, String expression, String defaultValue) {
        String extracted = extract(source, expression);

        return extracted == null || "".equals(extracted)
                ? defaultValue
                : extracted;
    }

    private String extract(Path sourcePath, String expression) {   // mstodo simplify, make the extract great again
        try (InputStream source = Files.newInputStream(sourcePath)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(source);
            XPathFactory xPathfactory = XPathFactory.newInstance();
            XPath xpath = xPathfactory.newXPath();
            XPathExpression expr = xpath.compile(expression);
            return (String) expr.evaluate(doc, XPathConstants.STRING);
        } catch (Exception any) {
            throw new RuntimeException("Failure when trying to find a match for " + expression, any);
        }
    }

    private String resolveUrlToFile(URL url) {
        try {
            String zipFile = Paths.get(url.toURI()).toFile().getAbsolutePath();
            if (zipFile == null) {
                try (InputStream stream = url.openStream()) {
                    zipFile = File.createTempFile("tt-dependency", ".jar").getAbsolutePath();
                    Files.copy(stream, Paths.get(zipFile));
                }
            }
            return zipFile;
        } catch (URISyntaxException | IOException e) {
            throw new RuntimeException("Unable to resolve: " + url);
        }
    }


    public static class ArtifactOrFile {
        private final String file;
        private final ArtifactSpec spec;

        private ArtifactOrFile(String file, ArtifactSpec spec) {
            this.file = file;
            this.spec = spec;
        }

        public String file() {
            return file;
        }

        public ArtifactSpec spec() {
            return spec;
        }

        public boolean isJar() {
            return spec != null;
        }

        public boolean hasSpec() {
            return !spec.groupId().equals(FAKE_GROUP_ID);
        }

        private static ArtifactOrFile file(String file) {
            return new ArtifactOrFile(file, null);
        }
        private static ArtifactOrFile spec(ArtifactSpec spec) {
            return new ArtifactOrFile(spec.file.getAbsolutePath(), spec);
        }
    }
}
