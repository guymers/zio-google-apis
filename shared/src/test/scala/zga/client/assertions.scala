package zga.client

import zga.error.Error
import zio.Exit
import zio.test.Assertion
import zio.test.TestResult

import scala.reflect.ClassTag

def assertErrorIs[E <: Error: ClassTag](exit: Exit[Error, ?]): TestResult = {
  zio.test.assert(exit)(Assertion.failsWithA[E])
}
