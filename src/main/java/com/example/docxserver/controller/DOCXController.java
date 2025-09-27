package com.example.docxserver.controller;

import com.example.docxserver.service.DocxHtmlIdAligner;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;


@RestController()
@RequestMapping("/docx")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class DocxController {

    private final static String dir = "E:\\programFile\\AIProgram\\docxServer\\src\\main\\resources\\";

    @PostMapping("/comment")
    public void comment(@RequestBody List<String> ids, HttpServletResponse response) throws IOException {
        File docx = new File(dir + "香港中文大学（深圳）家具采购项目.docx");

        // Get the modified document
        XWPFDocument document = DocxHtmlIdAligner.debugSpanIdAndReturnDoc(docx, ids.get(0));

        // Set response headers for file download
        response.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        response.setHeader("Content-Disposition", "attachment; filename=\"modified_document.docx\"");

        // Write document to response output stream
        try (OutputStream out = response.getOutputStream()) {
            document.write(out);
            out.flush();
        } finally {
            document.close();
        }
    }
}
