package com.example.docxserver.controller;

import com.example.docxserver.service.DocxHtmlIdAligner;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.util.List;


@RestController()
@RequestMapping("/docx")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class DocxController {

    private final static String dir = "E:\\programFile\\AIProgram\\docxServer\\src\\main\\resources\\";

    @PostMapping("/comment")
    public void comment(@RequestBody List<String> ids) throws IOException {
        File docx = new File(dir + "香港中文大学（深圳）家具采购项目.docx");
        DocxHtmlIdAligner.debugSpanId(docx,ids.get(0));
    }
}
