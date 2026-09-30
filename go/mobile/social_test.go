package mobile

import (
	"encoding/json"
	"os"
	"strings"
	"testing"

	"github.com/idena-network/idena-go/common/hexutil"
)

const socialContract = "0x840e092e31e9656fF15E541505039ed77585338E"

func readBody(t *testing.T, name string) string {
	t.Helper()
	data, err := os.ReadFile("testdata/" + name)
	if err != nil {
		t.Fatal(err)
	}
	return strings.TrimSpace(string(data))
}

func decodeCalls(t *testing.T, res string) []ContractCall {
	t.Helper()
	var calls []ContractCall
	if err := json.Unmarshal([]byte(res), &calls); err != nil {
		t.Fatal(err)
	}
	return calls
}

// Mainnet block 11,300,968: one idena.social post (the idena.social-ui v12.9.0 release note).
func TestContractCallsFindsThePost(t *testing.T) {
	res, err := ContractCalls(readBody(t, "body-11300968.hex"), socialContract)
	if err != nil {
		t.Fatal(err)
	}
	calls := decodeCalls(t, res)
	if len(calls) != 1 {
		t.Fatalf("calls = %d, want 1", len(calls))
	}
	call := calls[0]
	if call.Hash != "0x92ce06f0f7dea6fa76467e768cb20b005f13893770436c3da93e9a6f1995c63b" {
		t.Errorf("hash = %s", call.Hash)
	}
	if !strings.EqualFold(call.From, "0xB12b1222D0CE70d03bd984a861c825A86aC67898") {
		t.Errorf("from = %s", call.From)
	}
	if call.Method != "makePost" || call.Index != 0 || len(call.Args) != 1 {
		t.Fatalf("call = %+v", call)
	}
	arg, err := hexutil.Decode(call.Args[0])
	if err != nil {
		t.Fatal(err)
	}
	var post struct {
		Message string `json:"message"`
	}
	if err := json.Unmarshal(arg, &post); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(post.Message, "idena.social-ui v12.9.0 released") {
		t.Errorf("message = %q", post.Message)
	}
}

// Mainnet block 11,200,000: an activation and an invitation, no contract call.
func TestContractCallsSkipsOtherTransactions(t *testing.T) {
	res, err := ContractCalls(readBody(t, "body-11200000.hex"), socialContract)
	if err != nil {
		t.Fatal(err)
	}
	if res != "[]" {
		t.Errorf("res = %s, want []", res)
	}
}

// Another contract's calls are not returned.
func TestContractCallsFiltersByContract(t *testing.T) {
	res, err := ContractCalls(readBody(t, "body-11300968.hex"), "0x18b0a55eb99AcA113f50eEBbdeAf6f96E789277f")
	if err != nil {
		t.Fatal(err)
	}
	if res != "[]" {
		t.Errorf("res = %s, want []", res)
	}
}

func TestContractCallsRejectsBadHex(t *testing.T) {
	if _, err := ContractCalls("0xzz", socialContract); err == nil {
		t.Error("no error for invalid hex")
	}
}
