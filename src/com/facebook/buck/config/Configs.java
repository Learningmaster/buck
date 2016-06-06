/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.config;

import com.facebook.buck.log.Logger;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility functions for working with {@link Config}s.
 */
public final class Configs {
  private static final Logger LOG = Logger.get(Configs.class);

  private static final String DEFAULT_BUCK_CONFIG_FILE_NAME = ".buckconfig";
  private static final String DEFAULT_BUCK_CONFIG_OVERRIDE_FILE_NAME = ".buckconfig.local";
  private static final String DEFAULT_BUCK_CONFIG_DIRECTORY_NAME = ".buckconfig.d";

  private static final Path GLOBAL_BUCK_CONFIG_FILE_PATH = Paths.get("/etc/buckconfig");
  private static final Path GLOBAL_BUCK_CONFIG_DIRECTORY_PATH = Paths.get("/etc/buckconfig.d");

  private Configs() {}

  /**
   * Create a Config from a set of values.
   */
  public static Config createConfig(RawConfig values) {
    return new Config(values, ConfigConfig.of().withOverrides(values));
  }

  /**
   * Generates a Buck config by merging configs from specified locations on disk.
   *
   * In order:
   *
   * <ol>
   *   <li>{@code /etc/buckconfig}</li>
   *   <li>Files (in lexicographical order) in {@code /etc/buckconfig.d}</li>
   *   <li>{@code <HOME>/.buckconfig}</li>
   *   <li>Files (in lexicographical order) in {@code <HOME>/buckconfig.d}</li>
   *   <li>{@code <PROJECT ROOT>/.buckconfig}</li>
   *   <li>{@code <PROJECT ROOT>/.buckconfig.local}</li>
   *   <li>Any overrides (usually from the command line)</li>
   * </ol>
   *
   * @param configConfig configuration of what to lookup to construct this config.
   * @return the resulting {@code Config}.
   * @throws IOException on any exceptions during the underlying filesystem operations.
   */
  public static Config createConfig(ConfigConfig configConfig) throws IOException {
    ImmutableList.Builder<Path> configFileBuilder = ImmutableList.builder();

    if (configConfig.isUsingGlobalConfig()) {
      configFileBuilder.addAll(listFiles(GLOBAL_BUCK_CONFIG_DIRECTORY_PATH));
      if (Files.isRegularFile(GLOBAL_BUCK_CONFIG_FILE_PATH)) {
        configFileBuilder.add(GLOBAL_BUCK_CONFIG_FILE_PATH);
      }

      Path homeDirectory = Paths.get(System.getProperty("user.home"));
      Path userConfigDir = homeDirectory.resolve(DEFAULT_BUCK_CONFIG_DIRECTORY_NAME);
      configFileBuilder.addAll(listFiles(userConfigDir));
      Path userConfigFile = homeDirectory.resolve(DEFAULT_BUCK_CONFIG_FILE_NAME);
      if (Files.isRegularFile(userConfigFile)) {
        configFileBuilder.add(userConfigFile);
      }
    }

    Optional<Path> root = configConfig.getProjectRoot();
    if (root.isPresent()) {
      Path configFile = root.get().resolve(DEFAULT_BUCK_CONFIG_FILE_NAME);
      if (Files.isRegularFile(configFile)) {
        configFileBuilder.add(configFile);
      }
      Path overrideConfigFile = root.get().resolve(DEFAULT_BUCK_CONFIG_OVERRIDE_FILE_NAME);
      if (Files.isRegularFile(overrideConfigFile)) {
        configFileBuilder.add(overrideConfigFile);
      }
    }

    ImmutableList<Path> configFiles = configFileBuilder.build();

    RawConfig.Builder builder = RawConfig.builder();
    for (Path file : configFiles) {
      try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        ImmutableMap<String, ImmutableMap<String, String>> parsedConfiguration = Inis.read(reader);
        LOG.debug("Loaded a configuration file %s: %s", file, parsedConfiguration);
        builder.putAll(parsedConfiguration);
      }
    }
    LOG.debug("Adding configuration overrides: %s", configConfig.getOverrides());
    builder.putAll(configConfig.getOverrides());
    return new Config(builder.build(), configConfig);
  }

  private static ImmutableSortedSet<Path> listFiles(Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      return ImmutableSortedSet.of();
    }
    try (DirectoryStream<Path> directory = Files.newDirectoryStream(root)) {
      return ImmutableSortedSet.<Path>naturalOrder().addAll(directory.iterator()).build();
    }
  }

}
