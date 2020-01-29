/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.util;

import io.github.fukkitmc.gloom.asm.GloomInjector;
import io.github.fukkitmc.gloom.definitions.GloomDefinitions;
import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.providers.MappingsProvider;
import net.fabricmc.loom.providers.MinecraftMappedProvider;
import net.fabricmc.loom.providers.MinecraftProvider;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import org.gradle.api.Project;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public class MapJarsTiny {
    public static void mapJars(MinecraftProvider jarProvider, MinecraftMappedProvider mapProvider, Project project) throws IOException {
        LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
        MappingsProvider mappingsProvider = extension.getMappingsProvider();

        GloomDefinitions definitions = extension.definitions;
        boolean dontTransform = definitions.getDefinitions().isEmpty();

        Path[] classpath = mapProvider.getMapperPaths().stream().map(File::toPath).toArray(Path[]::new);

        Path input = jarProvider.getMergedJar().toPath();
        Path outputMapped = mapProvider.getMappedJar().toPath();
        Path outputIntermediary = mapProvider.getIntermediaryJar().toPath();

        {
            project.getLogger().lifecycle(":remapping Minecraft (TinyRemapper, official -> intermediary)");

            TinyRemapper remapper = TinyRemapper.newRemapper()
                    .withMappings(TinyRemapperMappingsHelper.create(mappingsProvider.getMappings(), "official", "intermediary", true))
                    .renameInvalidLocals(true)
                    .rebuildSourceFilenames(true)
                    .build();

            try (OutputConsumerPath output = new OutputConsumerPath.Builder(outputIntermediary).build()) {
                output.addNonClassFiles(input);
                remapper.readClassPath(classpath);
                remapper.readInputs(input);

                remapper.apply(output);
            } catch (Exception e) {
                throw new RuntimeException("Failed to remap JAR " + input + " with mappings from " + mappingsProvider.tinyMappings, e);
            } finally {
                remapper.finish();
            }
        }

        project.getLogger().lifecycle(":remapping Minecraft (TinyRemapper, official -> named)");

        TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(TinyRemapperMappingsHelper.create(mappingsProvider.getMappings(), "official", "named", true))
                .extraPostVisitor(dontTransform ? null : visitor -> new GloomInjector(visitor, definitions))
                .renameInvalidLocals(true)
                .rebuildSourceFilenames(true)
                .build();

        try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(outputMapped).build()) {
            outputConsumer.addNonClassFiles(input);
            remapper.readClassPath(classpath);
            remapper.readInputs(input);
            remapper.apply(outputConsumer);
        } catch (Exception e) {
            throw new RuntimeException("Failed to remap JAR " + input + " with mappings from " + mappingsProvider.tinyMappings, e);
        } finally {
            remapper.finish();
        }
    }
}
