/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.testing.recording;

import static com.github.tomakehurst.wiremock.common.AbstractFileSource.byFileExtension;
import static com.github.tomakehurst.wiremock.common.Exceptions.throwUnchecked;

import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.common.JsonException;
import com.github.tomakehurst.wiremock.common.NotWritableException;
import com.github.tomakehurst.wiremock.common.TextFile;
import com.github.tomakehurst.wiremock.standalone.MappingFileException;
import com.github.tomakehurst.wiremock.standalone.MappingsSource;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.stubbing.StubMappings;
import io.opentelemetry.testing.internal.jackson.annotation.JsonInclude.Include;
import io.opentelemetry.testing.internal.jackson.core.JsonGenerator;
import io.opentelemetry.testing.internal.jackson.core.JsonParser;
import io.opentelemetry.testing.internal.jackson.core.JsonProcessingException;
import io.opentelemetry.testing.internal.jackson.databind.DeserializationFeature;
import io.opentelemetry.testing.internal.jackson.databind.ObjectMapper;
import io.opentelemetry.testing.internal.jackson.databind.ObjectWriter;
import io.opentelemetry.testing.internal.jackson.databind.SerializationFeature;
import io.opentelemetry.testing.internal.jackson.databind.cfg.JsonNodeFeature;
import io.opentelemetry.testing.internal.jackson.dataformat.yaml.YAMLGenerator;
import io.opentelemetry.testing.internal.jackson.dataformat.yaml.YAMLMapper;
import io.opentelemetry.testing.internal.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.extension.ExtensionContext;

// Mostly the same as
// https://github.com/wiremock/wiremock/blob/master/src/main/java/com/github/tomakehurst/wiremock/standalone/JsonFileMappingsSource.java
// replacing Json with Yaml.
final class YamlFileMappingsSource implements MappingsSource {

  private static final ObjectMapper yamlMapper =
      new YAMLMapper()
          .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
          .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
          // For non-YAML, follow
          // https://github.com/wiremock/wiremock/blob/master/src/main/java/com/github/tomakehurst/wiremock/common/Json.java#L41
          .setSerializationInclusion(Include.NON_NULL)
          .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
          .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
          .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
          .configure(JsonParser.Feature.IGNORE_UNDEFINED, true)
          .configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true)
          .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION);

  private static final ThreadLocal<ExtensionContext> currentTest = new ThreadLocal<>();

  private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\w-.]");

  static void setCurrentTest(ExtensionContext context) {
    currentTest.set(context);
  }

  private final FileSource mappingsFileSource;
  private final Map<UUID, StubMappingFileMetadata> fileNameMap;

  YamlFileMappingsSource(FileSource mappingsFileSource) {
    this.mappingsFileSource = mappingsFileSource;
    fileNameMap = new HashMap<>();
  }

  @Override
  public void save(List<StubMapping> stubMappings) {
    for (StubMapping mapping : stubMappings) {
      if (mapping != null && mapping.isDirty()) {
        save(mapping);
      }
    }
  }

  @Override
  public void save(StubMapping stubMapping) {
    StubMappingFileMetadata fileMetadata = fileNameMap.get(stubMapping.getId());
    if (fileMetadata == null) {
      ExtensionContext test = currentTest.get();
      Method method = test.getTestMethod().get();
      String filename = method.getDeclaringClass().getName() + "." + method.getName();
      fileMetadata = new StubMappingFileMetadata(sanitize(filename) + ".yaml", false);
    }

    if (fileMetadata.multi) {
      throw new NotWritableException(
          "Stubs loaded from multi-mapping files are read-only, and therefore cannot be saved");
    }

    String yaml = "";
    try {
      ObjectWriter objectWriter =
          yamlMapper.writerWithDefaultPrettyPrinter().withView(Json.PrivateView.class);
      yaml = objectWriter.writeValueAsString(stubMapping);
    } catch (IOException ioe) {
      throwUnchecked(ioe, String.class);
    }
    TextFile outFile = mappingsFileSource.getTextFileNamed(fileMetadata.path);
    // For multiple requests from the same test, we append as a multi-file yaml.
    if (Files.exists(Paths.get(outFile.getPath()))) {
      String existing = outFile.readContentsAsString();
      yaml = existing + yaml;
    }
    mappingsFileSource.writeTextFile(fileMetadata.path, yaml);

    fileNameMap.put(stubMapping.getId(), fileMetadata);
    stubMapping.setDirty(false);
  }

  @Override
  public void remove(StubMapping stubMapping) {
    StubMappingFileMetadata fileMetadata = fileNameMap.get(stubMapping.getId());
    if (fileMetadata.multi) {
      throw new NotWritableException(
          "Stubs loaded from multi-mapping files are read-only, and therefore cannot be removed");
    }

    mappingsFileSource.deleteFile(fileMetadata.path);
    fileNameMap.remove(stubMapping.getId());
  }

  @Override
  public void removeAll() {
    if (anyFilesAreMultiMapping()) {
      throw new NotWritableException(
          "Some stubs were loaded from multi-mapping files which are read-only, so remove all cannot be performed");
    }

    for (StubMappingFileMetadata fileMetadata : fileNameMap.values()) {
      mappingsFileSource.deleteFile(fileMetadata.path);
    }
    fileNameMap.clear();
  }

  private boolean anyFilesAreMultiMapping() {
    return fileNameMap.values().stream().anyMatch(input -> input.multi);
  }

  @Override
  public void loadMappingsInto(StubMappings stubMappings) {
    if (!mappingsFileSource.exists()) {
      return;
    }

    List<TextFile> mappingFiles =
        mappingsFileSource.listFilesRecursively().stream()
            .filter(byFileExtension("yaml"))
            .collect(Collectors.toList());
    for (TextFile mappingFile : mappingFiles) {
      try {
        List<StubMapping> mappings =
            yamlMapper
                .readValues(
                    yamlMapper.createParser(mappingFile.readContentsAsString()), StubMapping.class)
                .readAll();
        for (StubMapping mapping : mappings) {
          mapping.setDirty(false);
          stubMappings.addMapping(mapping);
          StubMappingFileMetadata fileMetadata =
              new StubMappingFileMetadata(mappingFile.getPath(), mappings.size() > 1);
          fileNameMap.put(mapping.getId(), fileMetadata);
        }
      } catch (JsonProcessingException e) {
        throw new MappingFileException(
            mappingFile.getPath(), JsonException.fromJackson(e).getErrors().first().getDetail());
      } catch (IOException e) {
        throwUnchecked(e);
      }
    }
  }

  private static String sanitize(String s) {
    String decoratedString = String.join("-", s.split(" "));
    return NON_ALPHANUMERIC.matcher(decoratedString).replaceAll("").toLowerCase(Locale.ROOT);
  }

  private static class StubMappingFileMetadata {
    final String path;
    final boolean multi;

    StubMappingFileMetadata(String path, boolean multi) {
      this.path = path;
      this.multi = multi;
    }
  }
}
