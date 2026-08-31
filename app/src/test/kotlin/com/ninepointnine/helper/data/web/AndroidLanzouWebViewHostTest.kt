package com.ninepointnine.helper.data.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLanzouWebViewHostTest {
    @Test
    fun `folder probe reads the filename node without current nested size metadata`() {
        val script = buildLanzouFolderPageScript("\"\"")

        // Current Lanzou rows render the archive text in .filename and put the
        // human-readable size below it in .filesize. Keep this contract close
        // to the script so a future selector change cannot silently reintroduce
        // the concatenated `archive.zip2.0 M` name.
        assertTrue(script.contains("var nameNode=a.querySelector('.filename');"))
        assertTrue(
            script.contains(
                "var metadataNodes=nameNode.querySelectorAll('.filesize,.mmr,.filedown,.filetime,.file-time,.file-date,.size,.sizeh,#size,#time');",
            ),
        )
        assertTrue(script.contains("metadataNodes[metadataIndex].remove();"))
        assertTrue(script.contains("row.querySelector('#size, .size, .sizeh, .filesize')"))
        assertFalse(
            script.contains(
                "var nameNode=a.cloneNode(true);\n                var meta=nameNode.querySelector('.mmr');",
            ),
        )
    }
}
