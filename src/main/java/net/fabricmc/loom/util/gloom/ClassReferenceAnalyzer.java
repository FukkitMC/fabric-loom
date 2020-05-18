package net.fabricmc.loom.util.gloom;

import java.util.HashSet;
import java.util.Set;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

class ClassReferenceAnalyzer extends ClassVisitor {
	final Set<String> referenced = new HashSet<>();

	ClassReferenceAnalyzer(ClassVisitor classVisitor) {
		super(Opcodes.ASM8, classVisitor);
	}

	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		referenced.add(name);

		if (superName != null) {
			referenced.add(superName);
		}

		if (interfaces != null) {
			for (String i : interfaces) {
				addType(Type.getObjectType(i));
			}
		}

		super.visit(version, access, name, signature, superName, interfaces);
	}

	@Override
	public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
		addType(Type.getType(descriptor));
		return super.visitField(access, name, descriptor, signature, value);
	}

	@Override
	public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
		addType(Type.getReturnType(descriptor));

		for (Type type : Type.getArgumentTypes(descriptor)) {
			addType(type);
		}

		return super.visitMethod(access, name, descriptor, signature, exceptions);
	}

	private void addType(Type type) {
		if (type.getSort() == Type.OBJECT) {
			referenced.add(type.getInternalName());
		}
	}
}
