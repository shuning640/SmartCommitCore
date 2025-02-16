package com.github.smartcommit.core.dd;

import com.github.smartcommit.model.ChangedFile;
import com.github.smartcommit.model.Revision;
import com.github.smartcommit.model.TestFile;
import com.github.smartcommit.util.Utils;
import org.apache.commons.io.FileUtils;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

/**
 * @author lsn
 * @date 2023/10/30 4:24 PM
 */
public class Reducer {

    public void reduceTestCases(Revision revision, String testCase) {
        Iterator<ChangedFile> iterator = revision.getChangedFiles().iterator();
        String[] testCaseInfos= testCase.split("#");
        String testClassName = testCaseInfos[0];
        String testMethodName =testCaseInfos[1];

        while (iterator.hasNext()) {
            ChangedFile file = iterator.next();
            if (file instanceof TestFile) {
                if (!file.getNewPath().contains(testClassName.replace(".","/"))) {
                    iterator.remove();
                    continue;
                } else {
                    reduceTestCase((TestFile) file,testMethodName, revision.getLocalCodeDir());
                }
            }
        }
    }


    private void reduceTestCase(TestFile testFile,String testName, File rfcDir) {
        String path = testFile.getNewPath();
        File file = new File(rfcDir, path);
        try {
            CompilationUnit unit = Utils.parseCompliationUnit(FileUtils.readFileToString(file,
                    "UTF-8"));
            List<TypeDeclaration> types = Utils.castList(TypeDeclaration.class, unit.types());
            for (TypeDeclaration type : types) {
                MethodDeclaration[] mdArray = type.getMethods();
                for (int i = 0; i < mdArray.length; i++) {
                    MethodDeclaration method = mdArray[i];
                    String name = method.getName().toString();
                    if ((method.toString().contains("@Test") || name.startsWith("test") || name.endsWith("test")) && !name.contains(testName)) {
                        method.delete();
                    }
                }
            }
            List<ImportDeclaration> imports = Utils.castList(ImportDeclaration.class, unit.imports());
            int len = imports.size();
            ImportDeclaration[] importDeclarations = new ImportDeclaration[len];
            for (int i = 0; i < len; i++) {
                importDeclarations[i] = imports.get(i);
            }

            for (ImportDeclaration importDeclaration : importDeclarations) {
                String importName = importDeclaration.getName().getFullyQualifiedName();
                if (importName.lastIndexOf(".") > -1) {
                    importName = importName.substring(importName.lastIndexOf(".") + 1);
                }

                boolean flag = false;
                for (TypeDeclaration type : types) {
                    if (type.toString().contains(importName)) {
                        flag = true;
                    }
                }
                if (!(flag || importDeclaration.toString().contains("*"))) {
                    importDeclaration.delete();
                }
            }
            file.delete();
            FileUtils.writeStringToFile(file, unit.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
