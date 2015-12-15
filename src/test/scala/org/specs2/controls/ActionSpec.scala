package org.specs2
package controls

import Action._
import control.{Eval, Checked, Writer, Eff, Member, Effects, MemberNat}
import Eval._ 
import Checked.runChecked  
import Member._
import MemberNat._
import Writer._    
import Eff._  
import scalaz.{Writer => _, Reader => _,_}, Scalaz._, effect.IO

class ActionSpec extends Specification with ScalaCheck { def is = s2"""

 The action stack can be used to 
   compute values                      $computeValues
   stop when there is an error         $stop
   display log messages                $logMessages            
   collect warnings                    $collectWarnings
 
"""

  def computeValues =
    runWith(2, 3).map(_._1) must beRight(5)

  def stop =
    runWith(20, 30) must_== Left("too big")

  def logMessages = {
    val messages = new scala.collection.mutable.ListBuffer[String]  
    runWith(1, 2, m => messages.append(m))
    
    messages.toList === List("got the value 1", "got the value 2")
  }

  def collectWarnings =
    runWith(2, 3).map(_._2) must beRight(Vector("the sum is big: 5"))   
    

  /**
   * HELPERS
   */
  
  def runWith(i: Int, j: Int, printer: String => Unit = s => ()): Either[String, (Int, Vector[String])] =
    run(runEval(runChecked(runWarnings(runConsoleToPrinter(printer)(actions(i, j))))))
  
  def actions(i: Int, j: Int): Eff[ActionStack, Int] = for {
    x <- evalIO(IO(i))
    _ <- log("got the value "+x)
    y <- evalIO(IO(j))
    _ <- log("got the value "+y)
    s <- if (x + y > 10) Checked.ko("too big") else Checked.ok(x + y)
    _ <- if (s >= 5) warn("the sum is big: "+s) else Eff.unit[ActionStack]        
  } yield s

}