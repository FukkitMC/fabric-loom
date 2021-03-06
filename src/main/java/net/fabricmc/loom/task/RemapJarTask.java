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

package net.fabricmc.loom.task;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.fukkitmc.gloom.asm.Illuminate;
import io.github.fukkitmc.gloom.asm.InheritanceProvider;
import io.github.fukkitmc.gloom.definitions.GloomDefinitions;
import io.github.fukkitmc.gloom.emitter.EmitterProvider;
import io.github.fukkitmc.gloom.emitter.emitters.MixinEmitter;
import org.gradle.api.Project;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.tasks.Jar;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.transform.StringZipEntryTransformer;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.providers.MappingsProvider;
import net.fabricmc.loom.util.GradleSupport;
import net.fabricmc.loom.util.MixinRefmapHelper;
import net.fabricmc.loom.util.NestedJars;
import net.fabricmc.loom.util.TinyRemapperMappingsHelper;
import net.fabricmc.loom.util.accesswidener.AccessWidenerJarProcessor;
import net.fabricmc.loom.util.gloom.DebugMixinEmitter;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyRemapperInheritanceProvider;
import net.fabricmc.tinyremapper.TinyUtils;

public class RemapJarTask extends Jar {
	private RegularFileProperty input;
	private Property<Boolean> addNestedDependencies;
	private Property<Boolean> remapAccessWidener;

	public RemapJarTask() {
		super();
		input = GradleSupport.getfileProperty(getProject());
		addNestedDependencies = getProject().getObjects().property(Boolean.class);
		remapAccessWidener = getProject().getObjects().property(Boolean.class);
		// false by default, I have no idea why I have to do it for this property and not the other one
		remapAccessWidener.set(false);
	}

	@TaskAction
	public void doTask() throws Throwable {
		Project project = getProject();
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		Path input = this.getInput().getAsFile().get().toPath();
		Path output = this.getArchivePath().toPath();

		if (!Files.exists(input)) {
			throw new FileNotFoundException(input.toString());
		}

		MappingsProvider mappingsProvider = extension.getMappingsProvider();

		String fromM = "named";
		String toM = "intermediary";

		Set<File> classpathFiles = new LinkedHashSet<>(
				project.getConfigurations().getByName("compileClasspath").getFiles()
		);
		Path[] classpath = classpathFiles.stream().map(File::toPath).filter((p) -> !input.equals(p) && Files.exists(p)).toArray(Path[]::new);

		File mixinMapFile = mappingsProvider.mappingsMixinExport;
		Path mixinMapPath = mixinMapFile.toPath();

		TinyRemapper.Builder remapperBuilder = TinyRemapper.newRemapper();

		remapperBuilder = remapperBuilder.withMappings(TinyRemapperMappingsHelper.create(mappingsProvider.getMappings(), fromM, toM, false));

		if (mixinMapFile.exists()) {
			remapperBuilder = remapperBuilder.withMappings(TinyUtils.createTinyMappingProvider(mixinMapPath, fromM, toM));
		}

		project.getLogger().lifecycle(":remapping " + input.getFileName());

		StringBuilder rc = new StringBuilder("Remap classpath: ");

		for (Path p : classpath) {
			rc.append("\n - ").append(p.toString());
		}

		project.getLogger().debug(rc.toString());

		TinyRemapper remapper;

		String random = Integer.toString(ThreadLocalRandom.current().nextInt() & ~(1 << 31), 36);
		GloomDefinitions definitions = extension.definitions;
		TinyRemapper[] remapperA = new TinyRemapper[1];

		EmitterProvider<MixinEmitter> provider = new EmitterProvider<>(owner ->
				new DebugMixinEmitter(
						owner,
						random + "/itf/" + owner + "Interface",
						random + "/holder/" + owner + "Holder",
						random + "/mixin/" + owner + "Mixin",
						project.getLogger()::info));
		Illuminate illuminate = new Illuminate(definitions, provider, new InheritanceProvider() {
			private InheritanceProvider delegate;

			@Override
			public String resolveFieldOwner(String owner, String name, String descriptor) {
				if (delegate == null) {
					delegate = new TinyRemapperInheritanceProvider(remapperA[0]);
				}

				return delegate.resolveFieldOwner(owner, name, descriptor);
			}

			@Override
			public String resolveMethodOwner(String owner, String name, String descriptor) {
				if (delegate == null) {
					delegate = new TinyRemapperInheritanceProvider(remapperA[0]);
				}

				return delegate.resolveMethodOwner(owner, name, descriptor);
			}
		});

		remapper = remapperBuilder
				.extraPreVisitor(illuminate::createVisitor)
				.build();
		remapperA[0] = remapper;

		Set<String> mixins = new HashSet<>();

		try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(output).build()) {
			outputConsumer.addNonClassFiles(input);
			remapper.readClassPath(classpath);
			remapper.readInputs(input);
			remapper.apply(outputConsumer);

			provider.getEmitters().forEach((name, emitter) -> {
				if (emitter.shouldEmitHolder()) {
					outputConsumer.accept(emitter.getHolder(), write(remapper.getRemapper(), emitter::emitHolder));
				}

				if (emitter.shouldEmitInterface()) {
					outputConsumer.accept(emitter.getInterface(), write(remapper.getRemapper(), emitter::emitInterface));
				}

				if (emitter.shouldEmitMixin()) {
					mixins.add(emitter.getMixin());
					outputConsumer.accept(emitter.getMixin(), write(remapper.getRemapper(), emitter::emitMixin));
				}
			});
		} catch (Exception e) {
			remapper.finish();
			throw new RuntimeException("Failed to remap " + input + " to " + output, e);
		}

		if (getRemapAccessWidener().getOrElse(false) && extension.accessWidener != null) {
			extension.getJarProcessorManager().getByType(AccessWidenerJarProcessor.class).remapAccessWidener(output, remapper.getRemapper());
		}

		remapper.finish();

		if (!Files.exists(output)) {
			throw new RuntimeException("Failed to remap " + input + " to " + output + " - file missing!");
		}

		if (MixinRefmapHelper.addRefmapName(extension.getRefmapName(), extension.getMixinJsonVersion(), output)) {
			project.getLogger().debug("Transformed mixin reference maps in output JAR!");
		}

		if (getAddNestedDependencies().getOrElse(false)) {
			if (NestedJars.addNestedJars(project, output)) {
				project.getLogger().debug("Added nested jar paths to mod json");
			}
		}

		if (!mixins.isEmpty()) {
			String file = "mixins.gloom-generated." + random + ".json";
			int packageOffset = random.length() + 7;

			JsonObject config = new JsonObject();
			JsonArray list = new JsonArray();

			config.addProperty("required", true);
			config.addProperty("package", random + ".mixin");
			config.addProperty("compatibilityLevel", "JAVA_8");
			config.add("mixins", list);

			for (String entry : mixins) {
				list.add(entry.substring(packageOffset).replace('/', '.'));
			}

			ZipUtil.addEntry(output.toFile(), file, config.toString().getBytes());
			ZipUtil.transformEntry(output.toFile(), "fabric.mod.json", new StringZipEntryTransformer() {
				@Override
				protected String transform(ZipEntry zipEntry, String input) {
					JsonObject object = new Gson().fromJson(input, JsonObject.class);
					JsonArray mixins = object.getAsJsonArray("mixins");

					if (mixins == null) {
						mixins = new JsonArray();
						object.add("mixins", mixins);
					}

					mixins.add(file);
					return object.toString();
				}
			});
		}

		/*try {
			if (modJar.exists()) {
				Files.move(modJar, modJarUnmappedCopy);
				extension.addUnmappedMod(modJarUnmappedCopy);
			}

			Files.move(modJarOutput, modJar);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}*/
	}

	@InputFile
	public RegularFileProperty getInput() {
		return input;
	}

	@Input
	public Property<Boolean> getAddNestedDependencies() {
		return addNestedDependencies;
	}

	@Input
	public Property<Boolean> getRemapAccessWidener() {
		return remapAccessWidener;
	}

	private static byte[] write(Remapper remapper, Consumer<ClassVisitor> consumer) {
		ClassWriter writer = new ClassWriter(0);
		consumer.accept(new ClassRemapper(writer, remapper));
		return writer.toByteArray();
	}
}
