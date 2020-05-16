package net.fabricmc.loom.util.gloom;

import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

class ClassReferenceAnalyzer extends ClassVisitor {
	final Map<String, Boolean> referenced = new HashMap<>();

	ClassReferenceAnalyzer(ClassVisitor classVisitor) {
		super(Opcodes.ASM8, classVisitor);
	}

	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		referenced.put(name, false);

		if (superName != null) {
			referenced.put(superName, false);
		}

		if (interfaces != null) {
			for (String i : interfaces) {
				addType(Type.getObjectType(i), true);
			}
		}

		super.visit(version, access, name, signature, superName, interfaces);
	}

	@Override
	public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
		addType(Type.getType(descriptor), false);
		return super.visitField(access, name, descriptor, signature, value);
	}

	@Override
	public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
		addType(Type.getReturnType(descriptor), false);

		for (Type type : Type.getArgumentTypes(descriptor)) {
			addType(type, false);
		}

		return super.visitMethod(access, name, descriptor, signature, exceptions);
	}

	private void addType(Type type, boolean i) {
		if (type.getSort() == Type.OBJECT) {
			if (i) {
				referenced.put(type.getInternalName(), true);
			} else {
				referenced.putIfAbsent(type.getInternalName(), false);
			}
		}
	}
}
