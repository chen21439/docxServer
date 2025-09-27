package com.example.docxserver.util;

import org.apache.poi.xwpf.usermodel.XWPFComment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTMarkup;

import java.math.BigInteger;
import java.util.UUID;

public class CommentUtils {

    public static void createCommentForRun(XWPFDocument document,
                                           XWPFRun xwpfRun){
        BigInteger commentId = BigInteger.valueOf(100L);
        CTMarkup ctMarkup = xwpfRun.getCTR().addNewCommentReference();
        ctMarkup.setId(commentId);
        // 检查是否存在comments部分，如果不存在则创建
        if (document.getDocComments() == null) {
            document.createComments();
        }
        XWPFComment comment = document.getDocComments().createComment(commentId);

        comment.setAuthor("admin");

        XWPFParagraph paragraph = comment.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setText("aaa");

    }
}
