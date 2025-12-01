import spacy

def main():
    # 加载 en_core_web_sm 模型
    nlp = spacy.load("en_core_web_sm")

    # 测试加载模型后对文本进行处理
    doc = nlp("This is a test sentence.")

    # 输出每个 token 和其对应的词性标签
    print([(token.text, token.pos_) for token in doc])

# 让程序从这里开始运行
if __name__ == "__main__":
    main()
