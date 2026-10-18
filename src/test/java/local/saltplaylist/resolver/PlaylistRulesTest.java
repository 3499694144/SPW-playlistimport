package local.saltplaylist.resolver;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

public class PlaylistRulesTest {
    @Test public void missingAndTiedCandidatesFailClosedWithDiagnostics() {
        var messages = new ArrayList<String>();
        assertThrows(IOException.class, () -> HostBytecode.unique("test", List.of(), messages::add));
        assertTrue(messages.getFirst().contains("state=MISSING"));
        ClassModel model = ClassFile.of().parse(ClassFile.of().build(ClassDesc.of("TestHost"), builder -> {
            for (String name : List.of("candidateOne", "candidateTwo")) {
                builder.withMethodBody(name, MethodTypeDesc.ofDescriptor("()V"), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                        code -> code.return_());
            }
        }));
        assertThrows(IOException.class, () -> HostBytecode.unique("test", model.methods(), messages::add));
        assertTrue(messages.getLast().contains("candidateOne"));
        assertTrue(messages.getLast().contains("candidateTwo"));
    }
}
