package com.example.docxserver.service;

import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class DOCXService {

    private final static String dir = "E:\\programFile\\AIProgram\\docxServer\\src\\main\\resources\\";

    public void readDocx() throws IOException {
        Path docxPath = Paths.get(dir + "香港中文大学（深圳）家具采购项目" + ".docx");

        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(docxPath))) {

        }
    }

}
