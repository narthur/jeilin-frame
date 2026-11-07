; Vendor SCSI command 0xEB 0x01: read SDRAM over USB (debug).
; Lives in bank 2's free space; the dispatcher's vendor-opcode check at
; bank2:9464 is patched to LJMP here (see sdram_read.py).
;
; CDB (XDATA 0x146F..): EB 01 a3 a2 a1 a0 n1 n0
;   a3..a0  SDRAM bus address, big-endian
;   n1 n0   byte count, big-endian, even, non-zero
; The host must ask for exactly n bytes of data-in.
;
; Built from firmware routines' patterns:
;   SDRAM read port  - common:2765 (SFR page 4)
;   bulk-in FIFO     - INQUIRY, bank2:dab5 (SFR page 8)

        .area   CODE (ABS)
        .org    0xF685

PAGE    = 0x94          ; SFR page select
CDB     = 0x146F
STATUS  = 0x1460        ; CSW status byte
SENSE   = 0x1481        ; sense key
ASC     = 0x144E
CSW     = 0x94DD        ; dispatcher: send CSW
NEXT    = 0x946D        ; dispatcher: rest of the opcode checks (reloads A, ignores CY)
;
; PAGE is set again before every paged SFR access, as the stock code does.

entry:  mov     a,r7                    ; r7 = opcode
        cjne    a,#0xEB,not_eb
        sjmp    eb
not_eb: cjne    a,#0xEC,other
        ljmp    CSW                     ; 0xEC: unchanged, succeed doing nothing
other:  ljmp    NEXT

; Only reached when the host asked for 0 bytes (any other length for a bad CDB
; is rejected by the dispatcher first). The ORL BD below has no endpoint
; selected on this path and is untested there.
bad:    mov     dptr,#SENSE
        mov     a,#5                    ; ILLEGAL REQUEST
        movx    @dptr,a
        mov     dptr,#ASC
        mov     a,#0x24                 ; invalid field in CDB
        movx    @dptr,a
        mov     a,#1
finish: mov     dptr,#STATUS
        movx    @dptr,a
        mov     PAGE,#8
        orl     0xBD,#1
        ljmp    CSW

eb:     mov     dptr,#CDB+1
        movx    a,@dptr
        cjne    a,#0x01,bad
        ; byte count -> r6:r5, must be even and non-zero
        mov     dptr,#CDB+6
        movx    a,@dptr
        mov     r6,a
        inc     dptr
        movx    a,@dptr
        mov     r5,a
        jb      acc.0,bad
        orl     a,r6
        jz      bad

        ; program the SDRAM read port (page 4): address, count, start
        mov     PAGE,#4
        mov     0xA1,#1
        mov     dptr,#CDB+5             ; same order as common:2765, low byte first
        movx    a,@dptr
        mov     PAGE,#4
        mov     0xF5,a                  ; address bits 7..0
        mov     dptr,#CDB+4
        movx    a,@dptr
        mov     PAGE,#4
        mov     0xF6,a
        mov     dptr,#CDB+3
        movx    a,@dptr
        mov     PAGE,#4
        mov     0xF7,a
        mov     dptr,#CDB+2
        movx    a,@dptr
        mov     PAGE,#4
        mov     0xF9,a                  ; address bits 31..24
        mov     PAGE,#4
        mov     0xFA,r5                 ; count low
        mov     PAGE,#4
        mov     0xFB,r6                 ; count high
        mov     PAGE,#4
        orl     0xF4,#0x20              ; start read
        mov     PAGE,#4
        anl     0xF4,#0xDF
wready: mov     PAGE,#4
        mov     a,0xF4
        jnb     acc.6,wready            ; data ready

        ; point the FIFO at the bulk-in endpoint, as INQUIRY does via bank3:FA97(1, 0)
        mov     PAGE,#8
        mov     0xB1,#1
        mov     PAGE,#8
        mov     0xB2,#0

        ; copy count bytes: SDRAM data port -> bulk-in FIFO
        ; r6:r5 counts down; r6 is pre-incremented when r5 != 0 so the two DJNZs
        ; run exactly r6:r5 times
        ; ponytail: wready and wfifo wait forever; if the host stops reading
        ; mid-transfer the frame hangs until power-cycled. Add the drain loops'
        ; bail checks if that bites.
        mov     a,r5
        jz      loop
        inc     r6
loop:   mov     PAGE,#4
        mov     a,0xFD
        mov     r4,a
wfifo:  mov     PAGE,#8
        mov     a,0xB3
        jb      acc.0,wfifo             ; FIFO full
        mov     PAGE,#8
        mov     0xBC,r4
        djnz    r5,loop
        djnz    r6,loop

        ; wait for the FIFO to drain, as INQUIRY does (bails on bit 0x05 set or
        ; on 0xAD bit 7 clear)
wdone1: mov     PAGE,#8
        mov     a,0xB3
        jb      acc.1,wdone2
        jb      0x05,wdone2
        mov     PAGE,#0x0F
        mov     a,0xAD
        jb      acc.7,wdone1
wdone2: mov     PAGE,#8
        mov     a,0xBD
        jb      acc.3,ok
        jb      0x05,ok
        mov     PAGE,#0x0F
        mov     a,0xAD
        jb      acc.7,wdone2
ok:     clr     a
        ljmp    finish

; The dispatcher only runs a handler when the host's CBW data length and
; direction match what the device expects, which comes from b117 (length,
; returned in R4..R7) and ee27 (R7 = 1 for data-in). Both return 0 for EB, so
; their first instruction, MOV DPTR,#0x146F, is patched to jump here.

; length: n for a valid EB 01 CDB, else 0 (the dispatcher then rejects it,
; stalling the pipe as the BOT spec wants). Skipping b117's store to 0x0215 is
; fine: it's a scratch local that every user (927d, d2c5, d933) writes first.
len_hook:
        mov     dptr,#CDB
        movx    a,@dptr
        cjne    a,#0xEB,len_orig
        clr     a
        mov     r4,a
        mov     r5,a
        mov     r6,a
        mov     r7,a
        mov     dptr,#CDB+1
        movx    a,@dptr
        cjne    a,#0x01,len_ret
        mov     dptr,#CDB+7
        movx    a,@dptr
        jb      acc.0,len_ret           ; odd count: reject
        mov     r7,a
        mov     dptr,#CDB+6
        movx    a,@dptr
        mov     r6,a
len_ret: ret
len_orig: ljmp  0xB11A                  ; b117+3; dptr = CDB, as the original left it

; direction: EB is data-in
dir_hook:
        mov     dptr,#CDB
        movx    a,@dptr
        cjne    a,#0xEB,dir_orig
        mov     r7,#1
        ret
dir_orig: ljmp  0xEE2A                  ; ee27+3, past the patched MOV DPTR
