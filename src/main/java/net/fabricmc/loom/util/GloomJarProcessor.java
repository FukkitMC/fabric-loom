package net.fabricmc.loom.util;

import com.google.common.hash.Hashing;
import com.google.common.io.CharSource;
import io.github.fukkitmc.gloom.DefinitionSerializer;
import io.github.fukkitmc.gloom.asm.GloomInjector;
import io.github.fukkitmc.gloom.definitions.ClassDefinition;
import io.github.fukkitmc.gloom.definitions.GloomDefinitions;
import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.processors.JarProcessor;
import org.gradle.api.Project;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.transform.ByteArrayZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformerEntry;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;

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
    public void process(File file) {
        ZipUtil.transformEntries(file, createEntries());
        ZipUtil.addEntry(file, "gloom.sha256", hash);
    }

    private ZipEntryTransformerEntry[] createEntries() {
        return definitions.getDefinitions().stream()
                .map(clazz -> new ZipEntryTransformerEntry(clazz.getName().replaceAll("\\.", "/") + ".class", getTransformer(clazz)))
                .toArray(ZipEntryTransformerEntry[]::new);
    }

    private ZipEntryTransformer getTransformer(ClassDefinition clazz) {
        return new ByteArrayZipEntryTransformer() {
            @Override
            protected byte[] transform(ZipEntry zipEntry, byte[] input) {
                ClassReader reader = new ClassReader(input);
                ClassWriter writer = new ClassWriter(0);

                project.getLogger().lifecycle("Applying Gloom to " + clazz.getName());

                reader.accept(new GloomInjector(writer, definitions), 0);
                return writer.toByteArray();
            }
        };
    }

    @Override
    public boolean isInvalid(File file) {
        return Arrays.equals(ZipUtil.unpackEntry(file, "gloom.sha256"), hash);
    }
}
