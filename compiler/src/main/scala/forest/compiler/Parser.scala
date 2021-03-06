package forest.compiler

import forest.ast._
import scala.util.parsing.combinator.JavaTokenParsers

class Parser extends JavaTokenParsers {

  override def skipWhitespace = false
  
  val nl: Parser[String] = "\r?\n".r
  val blankLines = nl+
  val space: Parser[String] = "[ \t]".r
  
  
  object Xml {
    // Tag name http://www.w3.org/TR/html-markup/syntax.html#syntax-elements
    val tagName: Parser[String] = """[0-9a-zA-Z]+""".r
    // Attribute name http://www.w3.org/TR/html-markup/syntax.html#syntax-attributes
    val attr: Parser[String] = """[^\s\u0000"'>/=]+""".r 
  }
  
  // Forest expression language parsers (everything that will be between “{” and “}”)
  object Forest {
    // A path through an object (e.g. “foo.bar.baz”)
    val path: Parser[String] =
      rep1sep(ident, ".") ^^ { idents => idents.mkString(".") }
    
    val data: Parser[Data] =
      positioned(path ^^ Data)
    val inlineIf: Parser[InlineIf] =
      positioned((data ~ (" ? " ~> expr) ~ ((" : " ~> expr)?)) ^^ { case cond ~ thenPart ~ elsePart => InlineIf(cond, thenPart, elsePart) })
    val literal: Parser[Literal] =
      positioned(('\'' ~> "[^']+".r <~ '\'') ^^ Literal) // TODO handle quote escape, TODO double quote literals
  
    val expr: Parser[Expr] =
      literal | inlineIf | data
    
    val forGenerator: Parser[String~Data] =
      ("for " ~> ident) ~ (" <- " ~> data)
    
    val `if`: Parser[Expr] =
      "if " ~> expr
    
    val `else`: Parser[String] =
      "else"
    
    val call: Parser[Call] =
      positioned(("call " ~> path ~ ("(" ~> repsep(expr, ", ") <~ ")")) ^^ { case tmpl ~ params => Call(tmpl, params) })
  }
  
  def wrapped[A](p: Parser[A]): Parser[A] =
    "{" ~> p <~ "}"
  
  // Forest expression (enclosed in braces)
  def expr: Parser[Expr] =
    wrapped(Forest.expr)
  
  // Mix of raw text and forest expressions
  def textContent(until: Char): Parser[List[TextContent]] =
    (expr | (((not(elem(until) | "{" | nl) ~> ".".r)+) ^^ { cs => RawText(cs.mkString) }))+
  
  // http://www.w3.org/TR/html-markup/syntax.html#syntax-attr-unquoted
  // TODO handle HTML character references
  val unquotedValue: Parser[List[TextContent]] =
    (expr | ("""[^\s\u0000"'=<>`]+""".r ^^ RawText)) ^^ { List(_) }
  
  val quotedValue: Parser[List[TextContent]] =
    ('"' ~> textContent('"') <~ '"') | ("'" ~> textContent('\'') <~ "'")
  
  val attr: Parser[(String, List[TextContent])] =
    (Xml.attr ~ (('=' ~> (quotedValue | unquotedValue))?)) ^^ { case key ~ value => key -> value.getOrElse(Nil) }
  
  val attrs: Parser[Map[String, List[TextContent]]] =
    rep1sep(attr, space+) ^^ { as => as.toMap }
  
  val reference: Parser[String] =
    "/" ~> ident
  
  // Beginning of a HTML tag: name and attributes
  val tagPrefix: Parser[(String,Map[String, List[TextContent]], Option[String])] =
    (Xml.tagName ~ opt(space ~> attrs) ~ opt(space ~> reference)) ^^ { case name ~ attrs ~ ref => (name, attrs.getOrElse(Map.empty), ref) }
  
  val text: Parser[Text] =
    positioned(("| " ~> textContent('\0')) ^^ Text)
  
  // Expects at least `n` consecutive spaces. Returns the number of spaces.
  def indent(n: Int): Parser[Int] =
    (repN(n, space) ~> (space*)) ^^ { s => n + s.size }
  
  // From a given indentation value `n`, expects a tag, a blank line and children tags at a depth of `n + 1`
  def tree(n: Int): Parser[Node] = Parser { in =>
    
    // Retrieve the children of a given depth d (i.e. all the subtrees at a depth of at least d + 1)
    def children(d: Int): Parser[List[Node]] = ((blankLines ~> tree(d + 1))*)
    // Get the sibling of a node, at a given depth
    def sibling(d: Int): Parser[Node] = blankLines ~> tree(d)
    
    indent(n)(in) flatMapWithNext ( depth => // current node depth
        positioned((tagPrefix ~ children(depth)) ^^ { case (name, attrs, ref) ~ children => Tag(name, children, attrs, ref) })
      | positioned((wrapped(Forest.forGenerator) ~ children(depth)) ^^ { case ident ~ data ~ children => For(ident, data, children) })
      | positioned((wrapped(Forest.`if`) ~ children(depth) ~ ((blankLines ~> repN(depth, space) ~> wrapped(Forest.`else`) ~> children(depth))?)) ^^ { case expr ~ thenChildren ~ elseChildren => If(expr, thenChildren, elseChildren) })
      | positioned(wrapped(Forest.call))
      | text
    )
  }
  
  def any = Parser(in =>
    if (in.atEnd) {
      Failure("end of file", in)
    } else {
      Success(in.first, in.rest)
    })
  
  def brackets: Parser[String] = "[" ~ (rep((brackets | not("]") ~> any))) ~ commit("]") ^^ {
    case p1 ~ charList ~ p2 => p1 + charList.mkString + p2
  }
  
  val typeIdent: Parser[String] = ident ~ (brackets?) ^^ { case i ~ b => b match {
      case Some(b) => i + b
      case None => i
    }
  }
  
  val parameter: Parser[(String, String)] =
    (ident ~ (": " ~> typeIdent)) ^^ { case name ~ kind => (name -> kind) }
  
  // Template parameters
  val parameters: Parser[List[(String, String)]] =
    wrapped(repsep(parameter, ", "))
  
  val document: Parser[Document] =
    positioned((parameters ~ blankLines ~ tree(0)) ^^ { case p ~ _ ~ t => Document(p, t) })
  

  def parse(input: String): Either[String, Document] = {
    parseAll(document, input) match {
      case Success(document, _) => Right(document)
      case err: NoSuccess => Left(err.msg)
    }
  }

}
