package io.github.tony079.sdlc.sourcerepo.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.github.tony079.sdlc.sourcerepo.internal.BuildFileParser.Dependency;
import io.github.tony079.sdlc.sourcerepo.internal.JavaSymbolExtractor.Symbol;

import org.junit.jupiter.api.Test;

/** TDD 14.6 static inventory: Java symbols and build coordinates. Nothing is compiled or executed. */
class IndexerTest {

    @Test
    void javaTypesMethodsStereotypesAndMappings() {
        String source = """
                package com.acme.loans;

                import org.springframework.web.bind.annotation.*;

                @RestController
                @RequestMapping("/api/loans")
                public class LoanController {
                    @GetMapping("/{id}")
                    public Loan get(@PathVariable long id) { return null; }

                    @PostMapping
                    public Loan create(@RequestBody Loan loan) { return loan; }

                    record Loan(long id) { }
                }
                """;
        List<Symbol> symbols = JavaSymbolExtractor.extract("src/LoanController.java",
                source.getBytes(StandardCharsets.UTF_8));
        assertThat(symbols).anySatisfy(s -> {
            assertThat(s.kind()).isEqualTo("CLASS");
            assertThat(s.typeName()).isEqualTo("LoanController");
            assertThat(s.packageName()).isEqualTo("com.acme.loans");
            assertThat(s.stereotype()).isEqualTo("REST_CONTROLLER");
            assertThat(s.httpPath()).isEqualTo("/api/loans");
        });
        assertThat(symbols).anySatisfy(s -> {
            assertThat(s.memberName()).isEqualTo("get");
            assertThat(s.httpMethod()).isEqualTo("GET");
            assertThat(s.httpPath()).isEqualTo("/api/loans/{id}");
            assertThat(s.startLine()).isEqualTo(8);
        });
        assertThat(symbols).anySatisfy(s -> {
            assertThat(s.memberName()).isEqualTo("create");
            assertThat(s.httpMethod()).isEqualTo("POST");
            assertThat(s.httpPath()).isEqualTo("/api/loans");
        });
        assertThat(symbols).anySatisfy(s -> {
            assertThat(s.kind()).isEqualTo("RECORD");
            assertThat(s.typeName()).isEqualTo("LoanController.Loan");
        });
    }

    @Test
    void unparseableJavaYieldsNoSymbols() {
        assertThat(JavaSymbolExtractor.extract("Bad.java", "class { not java".getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
    }

    @Test
    void mavenDependenciesWithoutEntityExpansion() {
        String pom = """
                <?xml version="1.0"?>
                <project>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-dependencies</artifactId>
                      <version>3.3.0</version><scope>import</scope></dependency>
                  </dependencies></dependencyManagement>
                  <dependencies>
                    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId></dependency>
                  </dependencies>
                  <build><plugins><plugin><dependencies><dependency><artifactId>not-a-project-dependency</artifactId>
                  </dependency></dependencies></plugin></plugins></build>
                </project>
                """;
        List<Dependency> deps = BuildFileParser.maven("pom.xml", pom.getBytes(StandardCharsets.UTF_8));
        assertThat(deps).extracting(Dependency::artifactId).containsExactly("spring-boot-dependencies", "postgresql");
        assertThat(deps.get(0).scope()).isEqualTo("import");

        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE project [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <project><dependencies><dependency><groupId>&xxe;</groupId><artifactId>x</artifactId></dependency>
                </dependencies></project>
                """;
        List<Dependency> hostile = BuildFileParser.maven("pom.xml", xxe.getBytes(StandardCharsets.UTF_8));
        assertThat(hostile).allSatisfy(d -> assertThat(String.valueOf(d.groupId())).doesNotContain("root:"));
    }

    @Test
    void gradleDependencies() {
        String gradle = """
                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter-web:3.3.0'
                    testImplementation("org.junit.jupiter:junit-jupiter")
                    runtimeOnly "org.postgresql:postgresql:42.7.3"
                }
                """;
        List<Dependency> deps = BuildFileParser.gradle("build.gradle", gradle.getBytes(StandardCharsets.UTF_8));
        assertThat(deps).extracting(d -> d.groupId() + ":" + d.artifactId() + ":" + d.version()).containsExactly(
                "org.springframework.boot:spring-boot-starter-web:3.3.0", "org.junit.jupiter:junit-jupiter:null",
                "org.postgresql:postgresql:42.7.3");
    }
}
