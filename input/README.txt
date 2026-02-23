DROP YOUR INPUT FILES HERE
==========================

Supported formats:
  .docx   Word document  (text extracted automatically)
  .txt    Plain text file

Usage from the CoreNLP/ folder:
  .\run.ps1 ..\input\yourfile.docx
  .\run.ps1 ..\input\yourfile.txt

The script will:
  1. Extract the text from your document
  2. Compile SentimentTest.java (if needed)
  3. Run sentence-by-sentence sentiment analysis
  4. Print a score [-2, +2] per sentence and an overall average