package net.fabricmc.loom.util.gloom;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;

import com.google.common.hash.Hashing;
import com.google.common.io.CharSource;
import io.github.fukkitmc.gloom.DefinitionSerializer;
import io.github.fukkitmc.gloom.asm.GloomInjector;
import io.github.fukkitmc.gloom.definitions.ClassDefinition;
import io.github.fukkitmc.gloom.definitions.GloomDefinitions;
import org.gradle.api.Project;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.zeroturnaround.zip.ByteSource;
import org.zeroturnaround.zip.ZipEntrySource;
import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.transform.ByteArrayZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformerEntry;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.processors.JarProcessor;

public class GloomJarProcessor implements JarProcessor {
	private Project project;
	private GloomDefinitions definitions;
	private byte[] hash;

	@Override
	public void setup(Project project) {
		this.project = project;
		definitions = project.getExtensions().getByType(LoomGradleExtension.class).definitions;

		try {
			hash = CharSource.wrap(DefinitionSerializer.toString(definitions)).asByteSource(StandardCharsets.UTF_8).hash(Hashing.sha256()).asBytes();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void process(File file, File compileOnlyJar) {
		Map<String, Boolean> referenced = new HashMap<>();
		ZipUtil.transformEntries(file, createEntries(referenced));
		ZipUtil.addEntry(file, "gloom.sha256", hash);

		ZipEntrySource[] entries = createInjectionEntries(referenced);

		if (entries.length > 0) {
			if (!compileOnlyJar.exists()) {
				ZipUtil.pack(entries, compileOnlyJar);
			} else {
				ZipUtil.addEntries(compileOnlyJar, entries);
			}
		}
	}

	private ZipEntrySource[] createInjectionEntries(Map<String, Boolean> referenced) {
		return referenced.entrySet().stream()
				.map(entry -> {
					boolean isInterface = entry.getValue();
					int access = isInterface ? (Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE) : Opcodes.ACC_PUBLIC;
					ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);

					writer.visit(Opcodes.V1_8, access, entry.getKey(), null, isInterface ? null : "java/lang/Object", null);

					if (!isInterface) {
						MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
						constructor.visitVarInsn(Opcodes.ALOAD, 0);
						constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
						constructor.visitInsn(Opcodes.RETURN);
						constructor.visitEnd();
					}

					writer.visitEnd();

					return new ByteSource(entry.getKey() + ".class", writer.toByteArray());
				})
				.toArray(ZipEntrySource[]::new);
	}

	private ZipEntryTransformerEntry[] createEntries(Map<String, Boolean> referenced) {
		return definitions.getDefinitions().stream()
				.map(clazz -> new ZipEntryTransformerEntry(clazz.getName().replaceAll("\\.", "/") + ".class", getTransformer(clazz, referenced)))
				.toArray(ZipEntryTransformerEntry[]::new);
	}

	private ZipEntryTransformer getTransformer(ClassDefinition clazz, Map<String, Boolean> referenced) {
		return new ByteArrayZipEntryTransformer() {
			@Override
			protected byte[] transform(ZipEntry zipEntry, byte[] input) {
				ClassReader reader = new ClassReader(input);
				ClassWriter writer = new ClassWriter(0);

				project.getLogger().lifecycle("Applying Gloom to " + clazz.getName());

				ClassReferenceAnalyzer after = new ClassReferenceAnalyzer(writer);
				GloomInjector gloom = new GloomInjector(after, definitions);
				ClassReferenceAnalyzer before = new ClassReferenceAnalyzer(gloom);
				reader.accept(before, 0);

				Map<String, Boolean> r = after.referenced;
				before.referenced.keySet().forEach(r::remove);
				referenced.putAll(r);

				return writer.toByteArray();
			}
		};
	}

	@Override
	public boolean isInvalid(File file) {
		return !Arrays.equals(ZipUtil.unpackEntry(file, "gloom.sha256"), hash);
	}
}
