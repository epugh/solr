package org.apache.solr.response;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import org.apache.commons.io.output.TeeWriter;
import org.junit.Assert;
import org.junit.Test;

public class TestTee {

  @Test
  public void testTeeHappyPath() throws IOException {
    StringWriter sw = new StringWriter();
    sw.write("hi");
    System.out.println("Data in the StringWriter: " + sw);

    sw.close();

    StringWriter swA = new StringWriter();
    StringWriter swB = new StringWriter();

    TeeWriter tee = new TeeWriter(swA, swB);

    Writer w = new BufferedWriter(tee);

    w.write("hello");
    w.flush();

    Assert.assertEquals("hello", swA.toString());
    Assert.assertEquals("hello", swB.toString());

    w.write("good bye");
    w.flush();

    Assert.assertEquals("hellogood bye", swA.toString());

    w.write("boom");
    w.flush();

    Assert.assertEquals("hellogood byeboom", swB.toString());
  }
}
