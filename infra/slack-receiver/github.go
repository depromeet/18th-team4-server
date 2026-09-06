package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

type dispatchClient struct {
	baseURL string
	repo    string // owner/repo
	token   string
	http    *http.Client
}

func newDispatchClient(baseURL, repo, token string) *dispatchClient {
	return &dispatchClient{
		baseURL: baseURL,
		repo:    repo,
		token:   token,
		http:    &http.Client{Timeout: 5 * time.Second},
	}
}

func (c *dispatchClient) dispatch(inc *Incident) error {
	payload := map[string]any{
		"event_type": "incident-analysis",
		"client_payload": map[string]string{
			"deploy_sha":  inc.DeploySha,
			"fingerprint": inc.Fingerprint,
			"trace_id":    inc.TraceID,
			"message":     inc.Message,
			"stacktrace":  inc.Stacktrace,
		},
	}
	buf, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	url := fmt.Sprintf("%s/repos/%s/dispatches", c.baseURL, c.repo)
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(buf))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+c.token)
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("dispatch 실패 status=%d body=%s", resp.StatusCode, string(b))
	}
	return nil
}
