package net.fabricmc.tinyremapper;

import io.github.fukkitmc.gloom.asm.InheritanceProvider;

// Ugly package-private bypass since TR does expose a nice
// and easy way to resolve true member owners
public class TinyRemapperInheritanceProvider implements InheritanceProvider {
	private final TinyRemapper remapper;

	public TinyRemapperInheritanceProvider(TinyRemapper remapper) {
		this.remapper = remapper;
	}

	@Override
	public String resolveFieldOwner(String owner, String name, String descriptor) {
		ClassInstance instance = remapper.classes.get(owner);

		if (instance == null) {
			System.err.println("Could not find class with name " + owner);
			return owner;
		}

		MemberInstance member = instance.resolve(MemberInstance.MemberType.FIELD, MemberInstance.getFieldId(name, descriptor, remapper.ignoreFieldDesc));

		if (member == null) {
			System.err.println("Could not find field with owner=" + owner + " name=" + name + " descriptor=" + descriptor);
			return owner;
		}

		return member.cls.getName();
	}

	@Override
	public String resolveMethodOwner(String owner, String name, String descriptor) {
		ClassInstance instance = remapper.classes.get(owner);

		if (instance == null) {
			System.err.println("Could not find class with name " + owner);
			return owner;
		}

		MemberInstance member = instance.resolve(MemberInstance.MemberType.METHOD, MemberInstance.getMethodId(name, descriptor));

		if (member == null) {
			System.err.println("Could not find method with owner=" + owner + " name=" + name + " descriptor=" + descriptor);
			return owner;
		}

		return member.cls.getName();
	}
}
