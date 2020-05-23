package net.fabricmc.loom.util.gloom;

import java.util.function.Consumer;

import io.github.fukkitmc.gloom.definitions.SyntheticField;
import io.github.fukkitmc.gloom.emitter.emitters.MixinEmitter;

public class DebugMixinEmitter extends MixinEmitter {
	private final String name;
	private final Consumer<String> debug;

	public DebugMixinEmitter(String name, String itf, String holder, String mixin, Consumer<String> debug) {
		super(name, itf, holder, mixin);
		this.name = name;
		this.debug = debug;
	}

	@Override
	public String generateHolderSyntheticSetAccessor(SyntheticField field) {
		debug.accept(String.format("[%s] Generating synthetic static field setter for %s of type %s", name, field.getName(), field.getType().getDescriptor()));

		return super.generateHolderSyntheticSetAccessor(field);
	}

	@Override
	public String generateHolderSyntheticGetAccessor(SyntheticField field) {
		debug.accept(String.format("[%s] Generating synthetic static field getter for %s of type %s", name, field.getName(), field.getType().getDescriptor()));

		return super.generateHolderSyntheticGetAccessor(field);
	}

	@Override
	public String generateInterfaceSyntheticSetAccessor(SyntheticField field) {
		debug.accept(String.format("[%s] Generating synthetic field setter for %s of type %s", name, field.getName(), field.getType().getDescriptor()));

		return super.generateInterfaceSyntheticSetAccessor(field);
	}

	@Override
	public String generateInterfaceSyntheticGetAccessor(SyntheticField field) {
		debug.accept(String.format("[%s] Generating synthetic field getter for %s of type %s", name, field.getName(), field.getType().getDescriptor()));

		return super.generateInterfaceSyntheticGetAccessor(field);
	}
}
