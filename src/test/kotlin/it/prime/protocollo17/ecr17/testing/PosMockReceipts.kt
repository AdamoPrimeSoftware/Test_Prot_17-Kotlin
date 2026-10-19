package it.prime.protocollo17.ecr17.testing

/**
 * Righe S reali delle catture PosMock (A§9.11, `iaedll-pagamenti-20250708.txt`, terminale TID 10002912): payload
 * dallo STX escluso all'ETX escluso, riferiti come `pagamenti:<riga>`. Le LRC sono verificate in IngenicoCaptureTest.
 *
 * Stanno qui e non in IngenicoCaptureTest perché li usano anche i test del mascheramento a cavallo dei frame e della
 * vista dello scontrino ricomposto: gli stessi byte reali, una sola copia.
 */
object PosMockReceipts {

    // ─── Formato A (pagamenti:83-95): righe da 24 colonne, 0x7D in testa a ogni riga, 0x7E per la doppia altezza ───

    /** Prima riga dello scontrino di una X (0x7E prima di PAGAMENTO), 200 caratteri = 8 righe intere. */
    const val PAGAMENTI_83 = "100029120S}       MASTERCARD       }         MCCHIP         ~        PAGAMENTO       }" +
        "       THANK YOU        }       WORLDLINE        }                        }Eserc.   29122912       }" +
        "A.I.I.C.     88105100004"

    const val PAGAMENTI_86 = "100029120S}Data 08/07/25  Ora 11:21}TML 10002912 STAN 000004}Mod. Online     CTLS ICC}" +
        "AUT. 48294  OPER. 000040}AUTH.RESP.CODE        00}PAN     ************1017}SCAD                ****}" +
        "A.ID      A0000000041010"

    const val PAGAMENTI_89 = "100029120S}APPL            MC DEBIT}ATC 00B7  TCC 380  TT 00}TrCC 978     UN 7AFB2DB5}" +
        "TVR           0000008001}ARQC    4A7E1C857A1F9086}IAD  0110A04203A40000000}     000000000000300FF00}" +
        "          000000000300FF"

    const val PAGAMENTI_92 = "100029120S}CVM No CVM              }                        ~IMPORTO EUR         0,05}" +
        "                        }  TRANSACTION APPROVED  }                        }  ARRIVEDERCI E GRAZIE  }" +
        "                        "

    /** Ultima riga di soli avanzamenti carta: sei blocchi "}" + 24 spazi, poi ESC. */
    val PAGAMENTI_95 = "100029120S" + ("}" + " ".repeat(24)).repeat(6) + "\u001B"

    val FORMAT_A: List<String> = listOf(PAGAMENTI_83, PAGAMENTI_86, PAGAMENTI_89, PAGAMENTI_92, PAGAMENTI_95)

    // ─── Formato B (pagamenti:117-126): colonne da 24 senza separatori, frame da 200 tagliati a metà campo ───

    /** 0x7F in più prima di ACQUISTO (non occupa colonne); finisce a metà della data ("Data 08" | "/07/25"). */
    const val PAGAMENTI_117 = "100029120S          VISA                    VISA          \u007F        ACQUISTO        " +
        "       THANK YOU               WORLDLINE                                Eserc.          29122912" +
        "A.I.I.C.     88105100003Data 08"

    /** Righe S intermedie con il PAN troncato (BIN + ultime 4); 120 finisce a metà dell'AID ("A0000" | "000031010"). */
    const val PAGAMENTI_120 = "100029120S/07/25  Ora 12:28TML 10002912 STAN 000003Mod. Online     CTLS ICCAUT. 52658  " +
        "OPER. 000039AUTH.RESP.CODE        00VISA CONTACTLESS        PAN     438746******9993SCAD                ****" +
        "A.ID      A0000"

    const val PAGAMENTI_123 = "100029120S000031010APPL         VISA CREDITATC 03DD  TCC 380  TT 00TrCC 978     UN E01A0C00" +
        "TVR           0000000000ARQC    A23BB1F8A9ED8341IAD       06010A03A00000CVM No CVM              " +
        "                       "

    /** Ultima riga: 0x7F prima di IMPORTO EUR, poi "}}" + ESC. */
    const val PAGAMENTI_126 = "100029120S \u007FIMPORTO EUR         0,05                          TRANSACTION APPROVED" +
        "                            ARRIVEDERCI E GRAZIE  CONTRATTO #12345        }}\u001B"

    val FORMAT_B: List<String> = listOf(PAGAMENTI_117, PAGAMENTI_120, PAGAMENTI_123, PAGAMENTI_126)

    /** Seconde righe di altri due scontrini nel formato B, con PAN di 16 e 19 caratteri. */
    const val PAGAMENTI_140 = "100029120S/07/25  Ora 12:32TML 10002912 STAN 000004Mod. Online     CTLS ICCAUT. 76547  " +
        "OPER. 000040AUTH.RESP.CODE        00PAN     518619******1017SCAD                ****A.ID      " +
        "A0000000041010APPL           "
    const val PAGAMENTI_160 = "100029120S/07/25  Ora 12:35TML 10002912 STAN 000005Mod. Online     CTLS ICCAUT. 92815  " +
        "OPER. 000041AUTH.RESP.CODE        00PAN  679999*********3224SCAD                ****A.ID      " +
        "A0000000043060APPL           "
}
