/*
 *
 * Developed by Pascal Hildebrand.
 *
 * This file is part of passwordSafe.
 *
 *  passwordSafe is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  passwordSafe is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with passwordSafe.  If not, see <http://www.gnu.org/licenses/>.
 *
 */


package keepassSafe;

import javacard.framework.*;
import javacard.security.RandomData;
import javacard.security.*;
import javacardx.crypto.*;

public class passwordSafe extends Applet
{
	//Class Byte
	final static byte CLA_NUMBER = (byte) 0x80;
    
    //INS Bytes 
	final static byte INS_INIT       = (byte) 0x20;
	final static byte INS_PIN_VERIFY = (byte) 0x21;
	final static byte INS_CHANGE_PIN = (byte) 0x22;
	final static byte INS_PIN_RESET  = (byte) 0x23;
	final static byte INS_CARD_RESET  = (byte) 0x24;
	
	final static byte INS_PW_SET  = (byte) 0x30;
	final static byte INS_PW_GET  = (byte) 0x31;
	final static byte INS_PW_DEL  = (byte) 0x32;
	
	final static byte INS_CREATE_FILE   = (byte) 0x40;
    final static byte INS_UPDATE_BINARY = (byte) 0x41;
    final static byte INS_READ_BINARY   = (byte) 0x42;
    final static byte INS_DELETE_FILE   = (byte) 0x43;
    final static byte INS_FILE_SIZE     = (byte) 0x44;
    final static byte INS_GET_FILE_NAME = (byte) 0x45;
    
    //Variables for the PIN
    private final static byte PIN_MAX_TRIES  = (byte) 3;
    private final static byte PIN_MIN_LENGTH = (byte) 2;
    private final static byte PIN_MAX_LENGTH = (byte) 16;
    //Variables for the PUK
    private final static byte PUK_MAX_TRIES = (byte) 3;
    private final static byte PUK_LENGTH    = (byte) 8;
    
    //Values for the State
    private static final byte STATE_INIT           = (byte) 0x00; 
    private static final byte STATE_SECURE_NO_DATA = (byte) 0x01; 
    private static final byte STATE_SECURE_DATA    = (byte) 0x02; 
    private static final byte STATE_PIN_LOCKED     = (byte) 0x03;
    
    //Values for the Master Password State
    private static final byte MASTER_PW_STORED_YES   = (byte) 0x01; 
    private static final byte MASTER_PW_STORED_NO    = (byte) 0x02; 
    
    // Status words
    public static final short SW_PIN_TRIES_REMAINING = (short)0x63C0;
    public static final short SW_COMMAND_NOT_ALLOWED_GENERAL = (short)0x6900;
    public static final short SW_CARD_LOCKED = (short)0x6250;
    public static final short SW_PUK_CORRECT = (short)0x9090;
    
    //Variables for states
    private byte state;
    private byte masterPW;
    private byte masterPWlength;
    
    //Variables for PIN, PUK, Filesystem and RandomData
    private short[] offset_data;
    private byte[] temp_data;
    private OwnerPIN pin = null;
    private OwnerPIN puk = null;
	private RandomData randomKey;
	private fileSystem myfile;

	private byte[] aesKey;
	
	//Method which is called one time Applet is beeing installed
	public static void install(byte[] buffer, short offset, byte length) {
        new passwordSafe(); 
    } 
    
    //Method which is called one Time on first start
    private passwordSafe() {
    	
    	//Initialize PIN and PUK
	    pin = new OwnerPIN(PIN_MAX_TRIES, PIN_MAX_LENGTH);
        puk = new OwnerPIN(PUK_MAX_TRIES, PUK_LENGTH);
		
		//Set state
        state = STATE_INIT;
        masterPW = MASTER_PW_STORED_NO;
        masterPWlength = (byte)0; 

		aesKey = new byte[32];
		
		//Create RAM Array
        temp_data = JCSystem.makeTransientByteArray((short)2, JCSystem.CLEAR_ON_DESELECT);
        
        //Create instance of Filesystem Class
        myfile = new fileSystem();
        
        //Init Variable for Random Data
        randomKey = RandomData.getInstance(RandomData.ALG_SECURE_RANDOM);
        
        //Register Applet
        register(); 
    }

	//Method called when deselected
	//Resets entered PIN and PUK
    public void deselect() { 
        pin.reset();
        puk.reset();
    }
    
    //Method called when selected
    //Checks if PIN and PUK is blocked
    public boolean select() {
    	//Garbage Collector
    	JCSystem.requestObjectDeletion();
    	//Check if tries remaining
    	//If not delete Data and block applet
	    if(pin.getTriesRemaining() == 0 && puk.getTriesRemaining() == 0) {
	    	ISOException.throwIt((short)(SW_CARD_LOCKED));
	    	myfile.deleteFile(myfile.keepassData1);
	        myfile.deleteFile(myfile.keepassData2);
	        myfile.deleteFile(myfile.keepassFileName);
	        myfile.deleteFile(myfile.keepassPW);
		    return false;
	    }
	    
	    return true;
    }

	//Method called when Data incoming
	public void process(APDU apdu) {
		//Create Variables
		byte buffer[] = apdu.getBuffer();
		byte cla = buffer[ISO7816.OFFSET_CLA];
        byte ins = buffer[ISO7816.OFFSET_INS];
        
        //When Applet selected send Data back with State value
        if(selectingApplet()) {
			buffer[0] = state;
            apdu.setOutgoingAndSend((short) 0, (short) 1);
			return;
        }
        
        //Check if right CLA Byte
        if(cla == CLA_NUMBER) {
        	
	        switch (ins) {
	        	//Personal Data
	        	//INS = 0x20
				case INS_INIT:
					cardINIT(apdu);
					break;
				//INS = 0x21
				case INS_PIN_VERIFY:
					checkPIN(apdu);
					break;	
				//INS = 0x22	
				case INS_CHANGE_PIN:
					changePIN(apdu);
					break;
				//INS = 0x23
				case INS_PIN_RESET:
					resetPIN(apdu);
					break;
				//INS = 0x24
				case INS_CARD_RESET:
					cardReset(apdu);
					break;
					
				//Master Password
				//INS = 0x30
				case INS_PW_SET:
					setMasterPW(apdu);
					break;
				//INS = 0x31
				case INS_PW_GET:
					getMasterPW(apdu);
					break;
				//INS = 0x32
				case INS_PW_DEL:
					delMasterPW(apdu);
					break;
						
				//Data Storage
				//INS = 0x40
				case INS_CREATE_FILE:
					createFile(apdu);
					break;
				//INS = 0x41
				case INS_UPDATE_BINARY:
					writeFile(apdu);
					break;
				//INS = 0x42
				case INS_READ_BINARY:
					readFile(apdu);
					break;
				//INS = 0x43
				case INS_DELETE_FILE:
					deleteFile(apdu);
					break;
				//INS = 0x44
				case INS_FILE_SIZE:
					getFileSize(apdu);
					break;
				//INS = 0x45
				case INS_GET_FILE_NAME:
					getFileName(apdu);
					break;	
					
				default:
					ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
	        }
	        
        } else {
        	
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
        }
	}
	
	//Method for card personalisation
	//APDU incoming Data
	//CLA = 0x80; INS = 0x20; P1 = 0x00; P2 = 0x01
	private void cardINIT(APDU apdu) throws ISOException {
		//Create Variables
		byte[] buf = apdu.getBuffer();
		byte p1 = buf[ISO7816.OFFSET_P1];
        byte p2 = buf[ISO7816.OFFSET_P2];
        short offset_cdata;
        short lc;
        
        //Check lenght field
        lc = apdu.setIncomingAndReceive();
        if(lc != apdu.getIncomingLength()) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        
        //Get the Offset of the Data
        offset_cdata = apdu.getOffsetCdata();
        
        //Check if the State is correct
        if(state != STATE_INIT) {
	        ISOException.throwIt(ISO7816.SW_COMMAND_NOT_ALLOWED);
        }
        
        //Check if P1 and P2 are correct
        if(p1 != (byte)0x00 && p2 != (byte)0x01) {
	        ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }
        
        //Check Data length
        if(lc > PIN_MAX_LENGTH || lc < PIN_MIN_LENGTH) {
	        ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        
        //Create Hash Value of the PIN and store it in aesKey
        MessageDigest hash = MessageDigest.getInstance(MessageDigest.ALG_SHA_256, false);
        hash.reset();
        hash.doFinal(buf, offset_cdata, lc, aesKey, (short)0x00);
        
        //Pad the PIN to max length and update PIN Variable
        Util.arrayFillNonAtomic(buf, (short)(offset_cdata + lc), (short)(PIN_MAX_LENGTH - lc), (byte) 0x00);
        pin.update(buf, offset_cdata, PIN_MAX_LENGTH);
        pin.resetAndUnblock();
        
        //Generate Random PUK
        randomKey.generateData(buf, (short) 0, PUK_LENGTH);
	
		//Update PUK
		puk.update(buf, (short)0, PUK_LENGTH);
		puk.resetAndUnblock();
		
		//Set new State
		state = STATE_SECURE_NO_DATA;

		//Send PUK back
        apdu.setOutgoingAndSend((short) 0, PUK_LENGTH); 
	}
	
	//Method for PIN verification
	//CLA = 0x80; INS = 0x21; P1 = 0x01; P2 = 0x00; Data = PIN
	private void checkPIN(APDU apdu) throws ISOException {
		byte[] buf = apdu.getBuffer();
		byte p1 = buf[ISO7816.OFFSET_P1];
        byte p2 = buf[ISO7816.OFFSET_P2];
        short offset_cdata;
        short lc;
        
        //Check lenght field
        lc = apdu.setIncomingAndReceive();
        if (lc != apdu.getIncomingLength()) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        //Get the Offset of the Data
        offset_cdata = apdu.getOffsetCdata();
        
        //Check if the State is correct
        if (state == STATE_INIT) {
	        ISOException.throwIt(ISO7816.SW_COMMAND_NOT_ALLOWED);
        }
        
        //Check if P1 and P2 are correct
        if (p1 == (byte)0x01 && p2 == (byte)0x01) {
        	//Decrypt buffer
	        byte[] tmp = new byte[lc];
	        Util.arrayCopy(buf, offset_cdata, tmp, (short)0, lc);
	        byte[] decrypted = decryptData(tmp, lc);
	        
	        for(short i = 0; i < 16; i++){
		        if (decrypted[i] != (byte)0xFF){
			        lc = (short)(16 - i);
			        Util.arrayCopy(decrypted, (short)i, buf, offset_cdata, lc);
			        break;
		        }
	        }	                
        } else if (p1 != (byte)0x01 && p2 != (byte)0x00) {
	        ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }
        
        //Pad the PIN to max length
        Util.arrayFillNonAtomic(buf, (short)(offset_cdata + lc), (short)(PIN_MAX_LENGTH - lc), (byte) 0x00);
        
        //Check PIN
        if (! pin.check(buf, offset_cdata, PIN_MAX_LENGTH)) {
	        ISOException.throwIt((short)(SW_PIN_TRIES_REMAINING | pin.getTriesRemaining()));
	        
	        //PIN false send number of tries Remaining back
	        if (pin.getTriesRemaining() == 0) {
		        state = STATE_PIN_LOCKED;
	        }   
        } else {
        	//PIN correct, send Master PW state back
	        buf[0] = masterPW;
			apdu.setOutgoingAndSend((short) 0, (short) 1);
        }
        
	}

	//Method for PIN change 
	//CLA = 0x80; INS = 0x22; P1 = 0x00; P2 = 0x02;Data = OLD_PIN + NEW_PIN
	//Musst be padded, new Pin length check at phone
	private void changePIN(APDU apdu) throws ISOException {
		byte[] buf = apdu.getBuffer();
		byte p1 = buf[ISO7816.OFFSET_P1];
        byte p2 = buf[ISO7816.OFFSET_P2];
        short offset_cdata;
        short lc;
        
        //Check if PIN Flag is True
        if ( ! pin.isValidated() ) {
	        ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }
        
        //Check lenght field
        lc = apdu.setIncomingAndReceive();
        if ( lc != apdu.getIncomingLength() ) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        
        //Get the Offset of the Data
        offset_cdata = apdu.getOffsetCdata();
        
        //Check if P1 and P2 are correct
        if ( p1 == (byte)0x00 && p2 == (byte)0x01 ) {
        	//Decrypt buffer
	        byte[] tmp1 = new byte[(short)(lc / 2)];
	        byte[] tmp2 = new byte[(short)(lc / 2)];
	        Util.arrayCopy(buf, offset_cdata, tmp1, (short)0, (short)(lc / 2));
	        Util.arrayCopy(buf, (short)(offset_cdata + (lc / 2)), tmp2, (short)0, (short)(lc / 2));
	        
	        tmp1 = decryptData(tmp1, (short)(lc / 2));
	        tmp2 = decryptData(tmp2, (short)(lc / 2));
	        short L1 = 0,L2 = 0;
	        
	        for(short i = 0; i < 16; i++){
		        if (tmp1[i] != (byte)0xFF){
			        L1 = (short)(16 - i);
			        Util.arrayCopy(tmp1, (short)i, buf, offset_cdata, L1);
			        break;
		        }
	        }
	        
	        for(short o = 0; o < 16; o++){
		        if (tmp2[o] != (byte)0xFF){
			        L2 = (short)(16 - o);
			        Util.arrayCopy(tmp2, (short)o, buf, (short)(offset_cdata + L1), L2);
			        break;
		        }
	        }
	        lc = (short)(L1 + L2);
	        
        } else if (p1 != (byte)0x00 && p2 != (byte)0x02) {
	        ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }
        
        //Check if length is 2 * Max PIN
        if ( lc != (short)(2 * PIN_MAX_LENGTH) ) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        
        //Check if Old PIN is Correct
        if ( !pin.check(buf, offset_cdata, PIN_MAX_LENGTH) ) {
	        ISOException.throwIt((short)(SW_PIN_TRIES_REMAINING | pin.getTriesRemaining()));
	        
	        if (pin.getTriesRemaining() == 0) {
		        state = STATE_PIN_LOCKED;
	        }  
        }
        
        //Update new PIN
        pin.update(buf, (short)(offset_cdata + PIN_MAX_LENGTH), PIN_MAX_LENGTH);
	}

	//Method for reseting the PIN
	//CLA = 0x80; INS = 23; P1 = 0x01; P2 = 02; Data = PUK + PIN 
	private void resetPIN(APDU apdu) throws ISOException {
		byte[] buf = apdu.getBuffer();
		byte p1 = buf[ISO7816.OFFSET_P1];
        byte p2 = buf[ISO7816.OFFSET_P2];
        short offset_cdata;
        short lc;
        
        //Check if PIN Flag is True
        if(! (pin.getTriesRemaining() == 0)) {
	        ISOException.throwIt((short)(SW_PIN_TRIES_REMAINING | pin.getTriesRemaining()));
        }
        
        //Check lenght field
        lc = apdu.setIncomingAndReceive();
        if(lc != apdu.getIncomingLength()) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        
        //Get the Offset of the Data
        offset_cdata = apdu.getOffsetCdata();
        //Check if the State is correct
        if(state == STATE_INIT) {
	        ISOException.throwIt(ISO7816.SW_COMMAND_NOT_ALLOWED);
        }
        
        //Check if P1 and P2 are correct
        if ( p1 == (byte)0x01 && p2 == (byte)0x01 ) {
        	//Decrypt buffer
	        byte[] tmp1 = new byte[(short)16];
	        
	        Util.arrayCopy(buf, offset_cdata, tmp1, (short)0, (short)16);
	        short L1 = 0,L2 = 0;
	        tmp1 = decryptData(tmp1, (short)16);
			L1 = PUK_LENGTH;
			
			Util.arrayCopy(tmp1, (short)8, buf, offset_cdata, L1);
	        
	        if (lc == (short)16){
		        byte[] tmp2 = new byte[(short)(lc / 2)];
		        Util.arrayCopy(buf, (short)(offset_cdata + (lc / 2)), tmp2, (short)0, (short)(lc / 2));
		        tmp2 = decryptData(tmp2, (short)(lc / 2));
		        for(short o = 0; o < 16; o++){
					if (tmp2[o] != (byte)0xFF){
						L2 = (short)(16 - o);
						Util.arrayCopy(tmp2, (short)o, buf, (short)(offset_cdata + L1), L2);
						break;
					}
				}
	        }
	        
	        lc = (short)(L1 + L2);
	        
        } else if (p1 != (byte)0x01 && p2 != (byte)0x02) {
	        ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }
        
        //Check if PUK is correct
        if(!puk.check(buf, offset_cdata, PUK_LENGTH)) {
	        ISOException.throwIt((short)(SW_PIN_TRIES_REMAINING | puk.getTriesRemaining()));
        }
        
        if ((byte)lc == PUK_LENGTH){
	        ISOException.throwIt(SW_PUK_CORRECT);
        } else if((byte)(lc - PUK_LENGTH) < PIN_MIN_LENGTH || (byte)(lc - PUK_LENGTH) > PIN_MAX_LENGTH) {
			ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
		}
		
		//Pad the PIN to max length
		Util.arrayFillNonAtomic(buf, (short)(offset_cdata + PUK_LENGTH + (lc - PUK_LENGTH)), (short)(PIN_MAX_LENGTH - (lc - PUK_LENGTH)), (byte) 0x00);
        
        //Update PIN
        pin.update(buf, (short)(offset_cdata + PUK_LENGTH), PIN_MAX_LENGTH);
	}
	
	//Method for Reset the Card
	//CLA = 0x80; INS = 24; P1 = 0x00; P2 = 00; Data = PUK
	private void cardReset(APDU apdu) throws ISOException {
		byte[] buf = apdu.getBuffer();
		byte p1 = buf[ISO7816.OFFSET_P1];
        byte p2 = buf[ISO7816.OFFSET_P2];
        short offset_cdata;
        short lc;
        
        //Check if PIN Flag is True
        if ( ! pin.isValidated() ) {
	        ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }
        
        //Check lenght field
        lc = apdu.setIncomingAndReceive();
        if ( lc != apdu.getIncomingLength() ) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        //Get the Offset of the Data
        offset_cdata = apdu.getOffsetCdata();
        
        //Check if P1 and P2 are correct
        if (p1 == (byte)0x00 && p2 == (byte)0x01) {
        	//Decrypt buffer
	        byte[] tmp1 = new byte[(short)16];
	        
	        Util.arrayCopy(buf, offset_cdata, tmp1, (short)0, (short)16);
	        tmp1 = decryptData(tmp1, (short)16);
			lc = PUK_LENGTH;
			
			Util.arrayCopy(tmp1, (short)8, buf, offset_cdata, lc);	                
        } else if ( p1 != (byte)0x00 && p2 != (byte)0x00 ) {
	        ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }

        //Check if PUK is correct
        if(! puk.check(buf, offset_cdata, PUK_LENGTH)) {
	        ISOException.throwIt((short)(SW_PIN_TRIES_REMAINING | puk.getTriesRemaining()));
        }
        
        //Delete all Data
        myfile.deleteFile(myfile.keepassData1);
	    myfile.deleteFile(myfile.keepassData2);
	    myfile.deleteFile(myfile.keepassFileName);
	    myfile.deleteFile(myfile.keepassPW);
        
        //Set state to Initial state
        state = STATE_INIT;
        masterPW = MASTER_PW_STORED_NO;
        masterPWlength = (byte)0; 
	}

	//Method for saving the Master Password
	//CLA = 0x80; INS = 30; P1 = 0x02; P2 = 01; Data = Master Password
	private void setMasterPW(APDU apdu) throws ISOException {
		byte[] buf = apdu.getBuffer();
		byte p1 = buf[ISO7816.OFFSET_P1];
        byte p2 = buf[ISO7816.OFFSET_P2];
        short offset_cdata;
        short lc;
        
        //Check if PIN Flag is True
        if(! pin.isValidated()) {
	        ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }
        
        //Check lenght field
        lc = apdu.setIncomingAndReceive();
        if(lc != apdu.getIncomingLength()) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        //Get the Offset of the Data
        offset_cdata = apdu.getOffsetCdata();
        //Check if the State is correct
        if(state == STATE_INIT || masterPW == MASTER_PW_STORED_YES) {
	        myfile.deleteFile(myfile.keepassPW);
	        masterPW = MASTER_PW_STORED_NO;
			masterPWlength = (byte)0; 
        }
        
        //Check if P1 and P2 are correct
        if (p1 == (byte)0x01 && p2 == (byte)0x01) {
        	//Decrypt buffer
	        byte[] tmp1 = new byte[(short)48];
	        Util.arrayCopy(buf, offset_cdata, tmp1, (short)0, (short)48);
	        
	        byte[] dec = decryptData(tmp1, (short)48);
			lc = (short)dec[0];
			
			Util.arrayCopy(dec, (short)(48 - lc), buf, offset_cdata, lc);	                
        } else if(p1 != (byte)0x02 && p2 != (byte)0x01) {
	        ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }

        //Create File for Password and write Data to File
        myfile.createFile(myfile.keepassPW, lc);
        myfile.writeDataToFile(myfile.keepassPW, (short)0, buf, offset_cdata, lc);
        
        //Save Password length and set new State
        masterPWlength = (byte)(lc & 0xff);
        masterPW = MASTER_PW_STORED_YES;
	}

	//Method for sending Master PW back
	//CLA = 0x80; INS = 31; P1 = 0x02; P2 = 02;
	private void getMasterPW(APDU apdu) throws ISOException {
		byte[] buf = apdu.getBuffer();
		byte p1 = buf[ISO7816.OFFSET_P1];
        byte p2 = buf[ISO7816.OFFSET_P2];
        short offset_cdata;
        short lc;
        
        //Check if PIN Flag is True
        if(! pin.isValidated()) {
	        ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }
        
        //Check lenght field
        lc = apdu.setIncomingAndReceive();
        if(lc != apdu.getIncomingLength()) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        
        //Get the Offset of the Data
        offset_cdata = apdu.getOffsetCdata();
        //Check if the State is correct
        if(state == STATE_INIT || masterPW == MASTER_PW_STORED_NO) {
	        ISOException.throwIt(ISO7816.SW_COMMAND_NOT_ALLOWED);
        }
        
        //Check if P1 and P2 are correct
        if (p1 == (byte)0x01 && p2 == (byte)0x02) {
	        byte[] tmpsen = new byte[48];
	        Util.arrayFillNonAtomic(tmpsen, (short)0x00, (short)48, (byte)0x00);
	        tmpsen[0] = (byte)masterPWlength; 
	        
	        short desOff = (short)(48 - (byte)masterPWlength);
	        byte readLen = (byte)masterPWlength;
	        byte[] tmp = myfile.readDataFromFile(myfile.keepassPW, (short)0, masterPWlength);
	        Util.arrayCopy(tmp, (short)0x00, tmpsen, desOff, readLen);
	        
	        tmpsen = encryptData(tmpsen, (short)48);
	        Util.arrayCopy(tmpsen, (short)0, buf, (short)0, (short)48);
			apdu.setOutgoingAndSend((short) 0, (short)48); 
			return;
        } else if(p1 != (byte)0x02 && p2 != (byte)0x02) {
	        ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }
        
        //Read Master PW from File and send back
        byte[] tmp = myfile.readDataFromFile(myfile.keepassPW, (short)0, masterPWlength);
        Util.arrayCopy(tmp, (short)0, buf, (short)0, masterPWlength);
        apdu.setOutgoingAndSend((short) 0, masterPWlength); 
	}

	//Method for deleting the Master Password
	//CLA = 0x80; INS = 32; P1 = 0x02; P2 = 03;
	private void delMasterPW(APDU apdu) throws ISOException {
		byte[] buf = apdu.getBuffer();
		byte p1 = buf[ISO7816.OFFSET_P1];
        byte p2 = buf[ISO7816.OFFSET_P2];
        short offset_cdata;
        short lc;
        
        //Check if PIN Flag is True
        if(! pin.isValidated()) {
	        ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }
        
        //Check lenght field
        lc = apdu.setIncomingAndReceive();
        if(lc != apdu.getIncomingLength()) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        
        //Get the Offset of the Data
        offset_cdata = apdu.getOffsetCdata();
        //Check if the State is correct
        if(state == STATE_INIT || masterPW == MASTER_PW_STORED_NO) {
	        ISOException.throwIt(ISO7816.SW_COMMAND_NOT_ALLOWED);
        }
        
        //Check if P1 and P2 are correct
        if(p1 != (byte)0x02 && p2 != (byte)0x03) {
	        ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }
        
        //Delete Password File and set new state
        myfile.deleteFile(myfile.keepassPW);
        masterPW = MASTER_PW_STORED_NO;
        masterPWlength = (byte)0;
	}

	//Method for Creating File
	//CLA = 0x80; INS = 40; P1 = 0x01; P2 = 01; Data = Filesize1 + Filesize2 + Filename
	private void createFile(APDU apdu) throws ISOException {
		byte[] buf = apdu.getBuffer();
		byte p1 = buf[ISO7816.OFFSET_P1];
        byte p2 = buf[ISO7816.OFFSET_P2];
        short offset_cdata;
        short lc;
        
        //Check if PIN Flag is True
        if( !pin.isValidated() ) {
	        ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }
        
        //Check lenght field
        lc = apdu.setIncomingAndReceive();
        if ( lc != apdu.getIncomingLength() ) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        
        //Get the Offset of the Data
        offset_cdata = apdu.getOffsetCdata();
        //Check if the State is correct
        if ( state == STATE_INIT || state == STATE_SECURE_DATA ) {
	        ISOException.throwIt(ISO7816.SW_COMMAND_NOT_ALLOWED);
        }
        
        //Check if P1 and P2 are correct
        if ( p1 != (byte)0x03 && p2 != (byte)0x01 ) {
	        ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }

		//Get Filesizes from Buffer
        Util.arrayCopy(buf, offset_cdata, temp_data, (short)0, (short)2);
        short tmp1 = Util.makeShort(temp_data[0], temp_data[1]);
        Util.arrayCopy(buf, (short)(offset_cdata + 2), temp_data, (short)0, (short)2);
        short tmp2 = Util.makeShort(temp_data[0], temp_data[1]);
        
        short bytesLC = (short) (buf[ISO7816.OFFSET_LC] & 0x00FF);
        
        //Create Files
        myfile.createFile(myfile.keepassData1, tmp1);
	    myfile.createFile(myfile.keepassData2, tmp2);
	    myfile.createFile(myfile.keepassFileName, (short)(bytesLC - 4));
	    
	    //Write Filename to File
	    myfile.writeDataToFile(myfile.keepassFileName, (short)0, buf, (short)(offset_cdata + 4), (short)(bytesLC - 4));
        
        //Set new State
		state = STATE_SECURE_DATA;
	}
	
	//Method for writing Data to File
	//CLA = 0x80; INS = 41; P1 = 0x03; P2 = 01 for File 1 and 02 for File 2; Data = FileOffset + Data
	private void writeFile(APDU apdu) throws ISOException {
		byte[] buf = apdu.getBuffer();
		byte p1 = buf[ISO7816.OFFSET_P1];
        byte p2 = buf[ISO7816.OFFSET_P2];
        short offset_cdata;
        short lc;
        
        //Check if PIN Flag is True
        if(! pin.isValidated()) {
	        ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }
        
        //Check lenght field
        lc = apdu.setIncomingAndReceive();
        if(lc != apdu.getIncomingLength()) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        
        //Get the Offset of the Data
        offset_cdata = apdu.getOffsetCdata();
        //Check if the State is correct
        if(state == STATE_INIT || state == STATE_SECURE_NO_DATA) {
	        ISOException.throwIt(ISO7816.SW_COMMAND_NOT_ALLOWED);
        }
        
        //Check if P1 is correct
        if(p1 != (byte)0x03) {
	        ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }
        
        //Get Length and Offset
        short data_length = (short)(lc - 2);
        short data_offset = (short)(offset_cdata + 2);
        short file_offset = Util.getShort(buf, offset_cdata);
        
        //Check which file should be modified
        //and write Data to this File
        if(p2 == (byte)0x01) {
	        
	        myfile.writeDataToFile(myfile.keepassData1, file_offset, buf, data_offset, data_length);
	        
        } else if (p2 == (byte)0x02) {
	        
	        myfile.writeDataToFile(myfile.keepassData2, file_offset, buf, data_offset, data_length);
	        
        } else {
	        ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }
	}
	
	//Method for reading Data from File
	//CLA = 0x80; INS = 42; P1 = 0x03; P2 = 01 for File 1 and 02 for File 2; Data = FileOffset
	private void readFile(APDU apdu) throws ISOException {
		byte[] buf = apdu.getBuffer();
		byte p1 = buf[ISO7816.OFFSET_P1];
        byte p2 = buf[ISO7816.OFFSET_P2];
        short offset_cdata;
        short lc;
        
        //Check if PIN Flag is True
        if(! pin.isValidated()) {
	        ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }
        
        //Check lenght field
        lc = apdu.setIncomingAndReceive();
        if(lc != apdu.getIncomingLength()) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        
        //Get the Offset of the Data
        offset_cdata = apdu.getOffsetCdata();
        //Check if the State is correct
        if(state == STATE_INIT || state == STATE_SECURE_NO_DATA) {
	        ISOException.throwIt(ISO7816.SW_COMMAND_NOT_ALLOWED);
        }
        
        //Check if P1 is correct
        if(p1 != (byte)0x03) {
	        ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }
        
        //Get length and Offset from Buffer
        short data_length = Util.getShort(buf, (short)(offset_cdata + 2));
        short file_offset = Util.getShort(buf, offset_cdata);
        
        //Check which file should be read from
        //and read Data from File
        if(p2 == (byte)0x01) {
	        
	        Util.arrayCopy(myfile.readDataFromFile(myfile.keepassData1, file_offset, data_length), (short)0, buf, (short)0, data_length);
	        
        } else if (p2 == (byte)0x02) {
	        
	        Util.arrayCopy(myfile.readDataFromFile(myfile.keepassData2, file_offset, data_length), (short)0, buf, (short)0, data_length);
	        
        } else {
	        ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }
		
		//Send Data back
        apdu.setOutgoingAndSend((short)0, data_length);
	}
	
	//Method for Deleting Files
	//CLA = 0x80; INS = 43; P1 = 0x03; P2 = 01 for File 1 and 02 for File 2 and 03 for both Files; 
	private void deleteFile(APDU apdu) throws ISOException {
		byte[] buf = apdu.getBuffer();
		byte p1 = buf[ISO7816.OFFSET_P1];
        byte p2 = buf[ISO7816.OFFSET_P2];
        short offset_cdata;
        short lc;
        
        //Check if PIN Flag is True
        if(! pin.isValidated()) {
	        ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }
        
        //Check lenght field
        lc = apdu.setIncomingAndReceive();
        if(lc != apdu.getIncomingLength()) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        
        //Get the Offset of the Data
        offset_cdata = apdu.getOffsetCdata();
        //Check if the State is correct
        if(state == STATE_INIT || state == STATE_SECURE_NO_DATA) {
	        ISOException.throwIt(ISO7816.SW_COMMAND_NOT_ALLOWED);
        }
        
        //Check if P1 is correct
        if(p1 != (byte)0x03) {
	        ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }
        
        //Check which file should be deleted and delete this File
        if(p2 == (byte)0x01) {
	        
	        myfile.deleteFile(myfile.keepassData1);
	        myfile.deleteFile(myfile.keepassFileName);
	        
        } else if (p2 == (byte)0x02) {
	        
	        myfile.deleteFile(myfile.keepassData2);
	        
        } else if (p2 == (byte)0x03) {
	        
	        myfile.deleteFile(myfile.keepassData1);
	        myfile.deleteFile(myfile.keepassData2);
	        myfile.deleteFile(myfile.keepassFileName);
	        
        } else {
	        ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }
    
        //Set new State
        state = STATE_SECURE_NO_DATA;
	}
	
	//Method for getting the File size
	//CLA = 0x80; INS = 44; P1 = 0x03; P2 = 04
	private void getFileSize(APDU apdu) throws ISOException {
		byte[] buf = apdu.getBuffer();
		byte p1 = buf[ISO7816.OFFSET_P1];
        byte p2 = buf[ISO7816.OFFSET_P2];
        short offset_cdata;
        short lc;
        
        //Check if PIN Flag is True
        if(! pin.isValidated()) {
	        ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }
        
        //Check lenght field
        lc = apdu.setIncomingAndReceive();
        if(lc != apdu.getIncomingLength()) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        
        //Get the Offset of the Data
        offset_cdata = apdu.getOffsetCdata();
        //Check if the State is correct
        if(state == STATE_INIT) {
	        ISOException.throwIt(ISO7816.SW_COMMAND_NOT_ALLOWED);
        }
        
        //Check if P1 and P2 are correct
        if(p1 != (byte)0x03 && p2 != (byte)0x04) {
	        ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }
        
        //Get the File size
        short tmp1 = myfile.getFileSize(myfile.keepassData1);
        short tmp2 = myfile.getFileSize(myfile.keepassData2);

		//Copy Data to Buffer
		Util.setShort(buf, (short)0, tmp1);
        Util.setShort(buf, (short)2, tmp2);
        
        //Send Buffer back
        apdu.setOutgoingAndSend((short)0, (short)4);
	}
	
	//Method for getting the File Name
	//CLA = 0x80; INS = 45; P1 = 0x01; P2 = 01
	private void getFileName(APDU apdu) throws ISOException {
		byte[] buf = apdu.getBuffer();
		byte p1 = buf[ISO7816.OFFSET_P1];
        byte p2 = buf[ISO7816.OFFSET_P2];
        short offset_cdata;
        short lc;
        
        //Check if PIN Flag is True
        if(! pin.isValidated()) {
	        ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }
        
        //Check lenght field
        lc = apdu.setIncomingAndReceive();
        if(lc != apdu.getIncomingLength()) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        
        //Get the Offset of the Data
        offset_cdata = apdu.getOffsetCdata();
        //Check if the State is correct
        if(state == STATE_INIT || state == STATE_SECURE_NO_DATA) {
	        ISOException.throwIt(ISO7816.SW_COMMAND_NOT_ALLOWED);
        }
        
        //Check if P1 and P2 are correct
        if(p1 != (byte)0x01 && p2 != (byte)0x01) {
	        ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }
        
        //Create length Variable
        short length = myfile.getFileSize(myfile.keepassFileName);
        
        //Read File Name from File and send it back
        Util.arrayCopy(myfile.readDataFromFile(myfile.keepassFileName, (short)0, length), (short)0, buf, (short)0, length);
		apdu.setOutgoingAndSend((short)0, length);
	}

	private byte[] encryptData(byte[] decryptedData, short len){
		Key key = KeyBuilder.buildKey(KeyBuilder.TYPE_AES_TRANSIENT_DESELECT, KeyBuilder.LENGTH_AES_256, false);
		byte[] enc = new byte[len];
		((AESKey)key).setKey(aesKey, (short)0);
		
		Cipher cipher = Cipher.getInstance(Cipher.ALG_AES_BLOCK_128_ECB_NOPAD, false);
		cipher.init(key, Cipher.MODE_ENCRYPT);
		cipher.doFinal(decryptedData, (short)0, len, enc, (short)0);
		
		return enc;
	}
	
	private byte[] decryptData(byte[] encryptedData, short len){
		Key key = KeyBuilder.buildKey(KeyBuilder.TYPE_AES_TRANSIENT_DESELECT, KeyBuilder.LENGTH_AES_256, false);
		((AESKey)key).setKey(aesKey, (short)0);
		
		byte[] dec = new byte[len];
		
		Cipher cipher = Cipher.getInstance(Cipher.ALG_AES_BLOCK_128_ECB_NOPAD, false);
		cipher.init(key, Cipher.MODE_DECRYPT);
		cipher.doFinal(encryptedData, (short)0, len, dec, (short)0);
		
		return dec;
	}
}
